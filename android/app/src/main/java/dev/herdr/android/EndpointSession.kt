package dev.herdr.android

import dev.herdr.android.endpoint.GraphicsTarget
import dev.herdr.android.endpoint.EndpointCodec
import dev.herdr.android.endpoint.EndpointMessage
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.SurfaceFrame
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One client-owned endpoint. Workspace/tab selection never uses the global API focus path. */
class EndpointSession(private val ssh: SshConnection, parentScope: CoroutineScope) : HerdrTransport {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val activationMutex = Mutex()
    private val geometryReady = CompletableDeferred<Unit>()
    private var active = false
    @Volatile private var wantsActive = false
    private val requestMutex = Mutex()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val ready = CompletableDeferred<Unit>()
    private val mutableSnapshot = MutableStateFlow<Snapshot?>(null)
    val snapshots = mutableSnapshot.asStateFlow()
    private val mutableSurface = MutableStateFlow<SurfaceFrame?>(null)
    val surfaces = mutableSurface.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    private val mutableEffects = MutableSharedFlow<EndpointMessage>(extraBufferCapacity = 32)
    val effects = mutableEffects.asSharedFlow()
    @Volatile private var channel: SshChannel? = null
    @Volatile private var bootId: String? = null
    private var serverVersion = ""
    private var methods = emptySet<String>()
    private var healthChecks = false
    @Volatile private var lastReceivedNanos = System.nanoTime()
    private var resyncing = false

    suspend fun start() {
        // remote-client-bridge can start a missing daemon; require an existing server first.
        ssh.request("ping", buildJsonObject {})
        channel = ssh.openClientChannel()
        scope.launch {
            try {
                while (true) receive(runInterruptible { EndpointCodec.readMessage(channel!!.input) })
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    val failure = IOException(error.message ?: "Connection to Herdr lost", error)
                    mutableFailure.value = failure.message
                    ready.completeExceptionally(failure)
                    pending.values.forEach { it.result.completeExceptionally(failure) }
                }
            } finally { channel?.close() }
        }
        try {
            send(EndpointCodec.hello(80, 24, 9, 18, surfaceActive = false))
            withTimeout(20_000) { ready.await() }
            scope.launch {
                while (true) {
                    delay(15_000)
                    if (healthChecks && System.nanoTime() - lastReceivedNanos > 45_000_000_000L) {
                        mutableFailure.value = "Connection to server lost"
                        channel?.close()
                        break
                    }
                    try { send(EndpointCodec.control("endpoint.health.ping.v1", buildJsonObject { put("nonce", UUID.randomUUID().toString()) }.toString())) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableFailure.value = error.description()
                        channel?.close()
                        break
                    }
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private suspend fun receive(message: EndpointMessage) {
        lastReceivedNanos = System.nanoTime()
        when (message) {
            is EndpointMessage.Control -> when (message.kind) {
                "endpoint.welcome.v1" -> {
                    val welcome = wireJson.parseToJsonElement(message.data).jsonObject
                    welcome["error"]?.takeIf { it.toString() != "null" }?.let { throw IOException(it.toString()) }
                    require(welcome["generation"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported endpoint generation" }
                    require(welcome.string("surface_codec") == "shell.surface.v1" && welcome.string("snapshot_codec") == "shell.snapshot.v1" &&
                        welcome.string("input_codec") == "shell.input.semantic.v1" && welcome.string("blob_codec") == "shell.blob.v1") { "Incompatible Herdr codecs" }
                    serverVersion = welcome.string("server_version") ?: ""
                    methods = welcome["methods"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                    healthChecks = welcome["capabilities"]?.jsonArray?.any { it.jsonPrimitive.content == "health_check" } == true
                }
                "shell.snapshot.v1" -> {
                    val raw = wireJson.parseToJsonElement(message.data).jsonObject
                    val nextBoot = raw.string("boot_id") ?: throw IOException("Snapshot is missing boot_id")
                    if (bootId != null && bootId != nextBoot) throw IOException("Server restarted. Reconnect to continue.")
                    if (mutableSurface.value?.bootId != nextBoot) mutableSurface.value = null
                    bootId = nextBoot
                    mutableSnapshot.value = raw.toSnapshot(serverVersion)
                    ready.complete(Unit)
                }
            }
            is EndpointMessage.Surface -> {
                if (bootId == null || message.value.bootId == bootId) {
                    mutableSurface.value = message.value
                    resyncing = false
                }
            }
            is EndpointMessage.Patch -> {
                if (!resyncing) {
                    try {
                        mutableSurface.value = (mutableSurface.value ?: throw IOException("No baseline frame available")).applyPatch(message.value)
                    } catch (_: IOException) {
                        resyncing = true
                        scope.launch {
                            try { resync() }
                            catch (error: Exception) { if (error !is CancellationException) mutableFailure.value = error.message }
                        }
                    }
                }
            }
            is EndpointMessage.ResponseChunk -> {
                if (message.bootId != bootId) throw IOException("Response belongs to a different server session")
                pending[message.requestId]?.let { request ->
                    if (request.bytes.size() + message.data.size > HerdrProtocol.MAX_RESPONSE_BYTES) throw IOException("Endpoint response is too large")
                    request.bytes.write(message.data)
                    if (message.finalChunk) {
                        try { request.result.complete(HerdrProtocol.response(message.requestId, request.bytes.toString("UTF-8"))) }
                        catch (error: Exception) { request.result.completeExceptionally(error) }
                    }
                }
            }
            is EndpointMessage.Error -> mutableEffects.tryEmit(message)
            is EndpointMessage.Shutdown -> throw IOException(message.reason ?: "Server shut down")
            else -> mutableEffects.tryEmit(message)
        }
    }

    fun supports(method: String): Boolean = method in methods

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        ready.await()
        mutableFailure.value?.let { throw IOException(it) }
        if (method !in methods) {
            if (method in LOCAL_METHODS) throw ApiException("unsupported_method", "Server does not support $method")
            return ssh.request(method, params)
        }
        return requestMutex.withLock { endpointRequest(method, params) }
    }

    private suspend fun endpointRequest(method: String, params: JsonObject): JsonObject {
        val id = UUID.randomUUID().toString()
        val request = Pending()
        check(pending.size < 128) { "Too many pending requests" }
        pending[id] = request
        return try {
            val envelope = buildJsonObject { put("id", id); put("method", method); put("params", params) }
            send(EndpointCodec.request(requireNotNull(bootId), envelope))
            withTimeout(30_000) { request.result.await() }
        } catch (error: TimeoutCancellationException) {
            mutableFailure.value = "Server did not respond to $method. The action may have completed; reconnect and check the result."
            channel?.close()
            throw error
        } finally { pending.remove(id) }
    }

    suspend fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        send(EndpointCodec.resize(columns, rows, cellWidth, cellHeight))
        geometryReady.complete(Unit)
    }

    suspend fun setActive(value: Boolean) {
        wantsActive = value
        activationMutex.withLock {
            if (value && !geometryReady.isCompleted) return@withLock
            if (active != value) {
                request("client_shell.surface.set", buildJsonObject { put("active", value) })
                active = value
            }
            focus(value)
        }
    }

    private suspend fun awaitGeometry() {
        try { withTimeout(10_000) { geometryReady.await() } }
        catch (error: TimeoutCancellationException) { throw IOException("Terminal viewport is not ready. Open the pane and try again.", error) }
    }

    suspend fun activate() { awaitGeometry(); setActive(true) }

    suspend fun resync() {
        awaitGeometry()
        activationMutex.withLock {
            if (!wantsActive) return@withLock
            request("client_shell.surface.set", buildJsonObject { put("active", true) })
            active = true
            focus(true)
        }
    }
    suspend fun focus(focused: Boolean) = send(EndpointCodec.focus(focused))
    suspend fun input(paneId: String, events: List<PaneInput>, popup: Boolean = false) =
        send(if (popup) EndpointCodec.popupInput(paneId, events) else EndpointCodec.paneInput(paneId, events))

    suspend fun pasteImage(target: GraphicsTarget, extension: String, bytes: ByteArray) =
        send(EndpointCodec.clipboardImage(target, extension, bytes))

    suspend fun detach() {
        try { send(EndpointCodec.detach()) } finally { close() }
    }

    private suspend fun send(bytes: ByteArray) = writeMutex.withLock {
        mutableFailure.value?.let { throw IOException(it) }
        val output = channel?.output ?: throw IOException("Endpoint is not connected")
        withContext(Dispatchers.IO) { runInterruptible { output.write(bytes); output.flush() } }
    }

    override fun close() {
        channel?.close()
        pending.values.forEach { it.result.cancel() }
        pending.clear()
        scope.cancel()
        ssh.close()
    }

    private class Pending(val result: CompletableDeferred<JsonObject> = CompletableDeferred(), val bytes: ByteArrayOutputStream = ByteArrayOutputStream())
    private companion object {
        val LOCAL_METHODS = setOf("workspace.focus", "tab.focus", "pane.focus", "client_shell.surface.set", "command.invoke")
    }
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.objects(key: String) = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()

internal fun JsonObject.toSnapshot(version: String): Snapshot {
    val agents = objects("agents").associateBy { it.string("pane_id") }
    val workspaceList = objects("workspaces").map { value ->
        val worktree = value["worktree"]?.takeIf { it.toString() != "null" }?.jsonObject
        Workspace(requireNotNull(value.string("workspace_id")), value.string("label").orEmpty(), value.string("active_tab_id"),
            value.string("agent_status") ?: "unknown", worktree?.let { Worktree(requireNotNull(it.string("key")), it.string("label").orEmpty()) })
    }
    val tabs = objects("tabs").map { value -> Tab(requireNotNull(value.string("tab_id")), requireNotNull(value.string("workspace_id")), value.string("label").orEmpty(), value["zoomed"]?.jsonPrimitive?.booleanOrNull ?: false) }
    val panes = objects("panes").map { value ->
        val id = requireNotNull(value.string("pane_id"))
        val agent = agents[id]
        Pane(id, requireNotNull(value.string("workspace_id")), requireNotNull(value.string("tab_id")),
            value.string("label") ?: agent?.string("name"), agent?.string("title"), agent?.string("agent"),
            value.string("foreground_cwd") ?: value.string("cwd"), agent?.string("agent_status") ?: "unknown")
    }
    val commands = objects("commands").map { value ->
        ServerCommand(requireNotNull(value.string("command_id")), value.string("description") ?: value.string("binding_label").orEmpty())
    }
    return Snapshot(version, workspaceList, tabs, panes, string("focused_workspace_id"), string("focused_tab_id"),
        string("focused_pane_id"), string("boot_id"), commands, get("revision")?.jsonPrimitive?.longOrNull ?: 0)
}
