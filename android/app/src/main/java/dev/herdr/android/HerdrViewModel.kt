package dev.herdr.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.android.endpoint.GraphicsTarget
import dev.herdr.android.endpoint.EndpointMessage
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey
import dev.herdr.android.endpoint.SurfaceFrame
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ServerState(
    val connecting: Boolean = false,
    val passwordRejected: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val snapshot: Snapshot? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val paneId: String? = null,
    val output: PaneOutput? = null,
    val history: Boolean = false,
    val drafts: Map<String, String> = emptyMap(),
    val paneSelections: Map<String, String> = emptyMap(),
    val sending: Set<String> = emptySet(),
    val paneErrors: Map<String, String> = emptyMap(),
    val uncertainDrafts: Map<String, String> = emptyMap(),
    val error: String? = null,
    val notice: String? = null,
) {
    fun draftKey(paneId: String): String = "${snapshot?.bootId.orEmpty()}:$paneId"
}

data class AppState(
    val profiles: List<ServerProfile> = emptyList(),
    val servers: Map<String, ServerState> = emptyMap(),
    val selectedId: String? = null,
    val storageError: String? = null,
)

data class ServerEffect(val serverId: String, val message: EndpointMessage)

class HerdrViewModel(private val store: ServerStore, val passwords: PasswordStore) : ViewModel() {
    private val mutableSettings = MutableStateFlow(ClientSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableSettingsReady = MutableStateFlow(false)
    val settingsReady = mutableSettingsReady.asStateFlow()
    fun updateSettings(value: ClientSettings) {
        mutableSettings.value = value
        viewModelScope.launch {
            try { store.saveSettings(value) }
            catch (error: Exception) { if (error is CancellationException) throw error; mutableState.update { it.copy(storageError = error.description()) } }
        }
    }
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val mutableSurfaces = MutableStateFlow<Map<String, SurfaceFrame?>>(emptyMap())
    val surfaces = mutableSurfaces.asStateFlow()
    private val mutableEffects = MutableSharedFlow<ServerEffect>(extraBufferCapacity = 32)
    val effects = mutableEffects.asSharedFlow()
    private val clients = mutableMapOf<String, HerdrClient>()
    private val endpoints = mutableMapOf<String, EndpointSession>()
    private val connections = mutableMapOf<String, Pair<SshConnection, Job>>()

    init {
        viewModelScope.launch {
            try { store.settings.collect { mutableSettings.value = it; mutableSettingsReady.value = true } }
            catch (error: Exception) { if (error is CancellationException) throw error; mutableState.update { it.copy(storageError = error.description()) }; mutableSettingsReady.value = true }
        }
        viewModelScope.launch {
            try {
                store.profiles.collect { profiles ->
                    mutableState.update { state -> state.copy(profiles = profiles, selectedId = state.selectedId
                        ?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(storageError = error.description()) }
            }
        }
    }

    private var foreground = true
    fun select(id: String) {
        mutableState.update { it.copy(selectedId = id) }
        focus(foreground)
    }

    fun save(profile: ServerProfile) {
        viewModelScope.launch {
            try {
                val previous = state.value.profiles.firstOrNull { it.id == profile.id }
                if (previous != null && previous.copy(name = profile.name) != profile) {
                    disconnect(profile.id)
                    withContext(kotlinx.coroutines.Dispatchers.IO) { passwords.forget(profile.id) }
                }
                store.save(profile)
                mutableState.update { it.copy(storageError = null) }
                if (previous?.copy(name = profile.name) != profile) disconnect(profile.id)
                select(profile.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(storageError = error.description()) }
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            try {
                disconnect(id)
                withContext(kotlinx.coroutines.Dispatchers.IO) { passwords.forget(id) }
                store.remove(id)
                disconnect(id)
                mutableState.update { it.copy(servers = it.servers - id, storageError = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(storageError = error.description()) }
            }
        }
    }

    fun connect(profile: ServerProfile, password: String, key: ByteArray?, savedPassword: PendingSavedPassword? = null) {
        var pendingPassword = password
        disconnect(profile.id)
        val ssh = SshConnection(profile)
        val endpoint = EndpointSession(ssh, viewModelScope)
        change(profile.id) { it.copy(connecting = true, history = false, error = null, notice = null, passwordRejected = false) }
        val job = viewModelScope.launch {
            try {
                try { ssh.connect(pendingPassword, key) }
                finally { pendingPassword = ""; key?.fill(0) }
                if (savedPassword != null) {
                    try { withContext(kotlinx.coroutines.Dispatchers.IO) { passwords.save(profile.id, savedPassword) } }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(storageError = "Password could not be saved. Connect manually next time.") }
                    }
                }
                endpoint.start()
                if (connections[profile.id]?.first !== ssh) return@launch
                endpoints[profile.id] = endpoint
                clients[profile.id] = HerdrClient(endpoint)
                change(profile.id) { it.copy(connecting = false, connected = true) }
                launch {
                    endpoint.snapshots.filterNotNull().collect { snapshot ->
                        if (endpoints[profile.id] !== endpoint) return@collect
                        change(profile.id) {
                            it.copy(workspaceId = snapshot.focusedWorkspaceId, tabId = snapshot.focusedTabId,
                                paneId = it.paneSelections["${snapshot.bootId}:${snapshot.focusedTabId}"] ?: snapshot.focusedPaneId).withSnapshot(snapshot)
                        }
                    }
                }
                launch { endpoint.surfaces.collect { frame -> if (endpoints[profile.id] === endpoint) mutableSurfaces.update { it + (profile.id to frame) } } }
                launch { endpoint.effects.collect { mutableEffects.emit(ServerEffect(profile.id, it)) } }
                focus(foreground)
                throw IOException(endpoint.failure.filterNotNull().first())
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (connections[profile.id]?.first === ssh) change(profile.id) { it.copy(connecting = false, connected = false, sending = emptySet(), error = error.description(),
                    passwordRejected = error is com.jcraft.jsch.JSchException && error.message.orEmpty().startsWith("Auth fail")) }
            } finally {
                coroutineContext.cancelChildren()
                key?.fill(0)
                withContext(NonCancellable) {
                    try { withTimeoutOrNull(1_000) { endpoint.detach() } }
                    catch (_: Exception) { /* Closing a failed connection is best effort. */ }
                    finally { endpoint.close() }
                }
                if (endpoints[profile.id] === endpoint) {
                    endpoints.remove(profile.id)
                    clients.remove(profile.id)
                }
                if (connections[profile.id]?.first === ssh) connections.remove(profile.id)
            }
        }
        job.invokeOnCompletion { pendingPassword = ""; key?.fill(0) }
        connections[profile.id] = ssh to job
    }

    fun disconnect(id: String) {
        connections.remove(id)?.second?.cancel()
        endpoints.remove(id)
        clients.remove(id)
        mutableSurfaces.update { it - id }
        change(id) { it.copy(connecting = false, connected = false, busy = false, sending = emptySet()) }
    }

    private val pendingGeometryClaims = mutableMapOf<String, String>()

    fun selectWorkspace(id: String, workspaceId: String) = navigate(id, "workspace.focus", "workspace_id", workspaceId)
    fun selectTab(id: String, tabId: String) = navigate(id, "tab.focus", "tab_id", tabId)
    fun selectPane(id: String, paneId: String?) {
        val pane = state.value.servers[id]?.snapshot?.panes?.firstOrNull { it.id == paneId } ?: return
        val server = state.value.servers[id] ?: return
        if (server.snapshot?.tabs?.find { it.id == pane.tabId }?.zoomed == true &&
            surfaces.value[id]?.panes?.none { it.paneId == pane.id } == true) {
            change(id) { it.copy(notice = "This tab is zoomed on the server. Restore the layout for everyone to open another pane.") }
            return
        }
        openPane(id, pane.workspaceId, pane.tabId, pane.id)
    }

    private fun openPane(id: String, workspaceId: String, tabId: String, paneId: String) {
        val endpoint = endpoints[id] ?: return
        val before = state.value.servers[id] ?: return
        fun selectLocal() = change(id) { it.copy(workspaceId = workspaceId, tabId = tabId, paneId = paneId,
            paneSelections = it.paneSelections + ("${it.snapshot?.bootId}:$tabId" to paneId), history = false) }
        if (before.workspaceId == workspaceId && before.tabId == tabId) {
            if (before.paneId != paneId) pendingGeometryClaims[id] = paneId
            selectLocal()
        }
        viewModelScope.launch {
            try {
                if (before.workspaceId != workspaceId) request(id, "workspace.focus", buildJsonObject { put("workspace_id", workspaceId) })
                if (before.tabId != tabId) request(id, "tab.focus", buildJsonObject { put("tab_id", tabId) })
                if (endpoints[id] === endpoint) {
                    selectLocal()

                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (endpoints[id] === endpoint) change(id) { it.copy(error = error.description()) }
            }
        }
    }

    private fun navigate(id: String, method: String, field: String, target: String) {
        viewModelScope.launch {
            try { request(id, method, buildJsonObject { put(field, target) }) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                change(id) { it.copy(error = error.description()) }
            }
        }
    }

    suspend fun request(id: String, method: String, params: JsonObject): JsonObject {
        val endpoint = endpoints[id] ?: throw IOException("Not connected to server")
        if (endpoint.supports(method) && method != "client_shell.surface.set") {
            check(state.value.selectedId == id && foreground) { "Open this server first" }
            endpoint.activate()
        }
        return endpoint.request(method, params)
    }

    fun created(id: String, result: JsonObject) {
        val moved = result["move_result"]?.jsonObject?.get("pane")
        val pane = (result["root_pane"] ?: result["pane"] ?: moved)?.jsonObject ?: return
        val workspaceId = pane["workspace_id"]?.jsonPrimitive?.content ?: return
        val tabId = pane["tab_id"]?.jsonPrimitive?.content ?: return
        val paneId = pane["pane_id"]?.jsonPrimitive?.content ?: return
        openPane(id, workspaceId, tabId, paneId)
    }

    fun draft(id: String, paneId: String, value: String) {
        change(id) { it.copy(drafts = it.drafts + (it.draftKey(paneId) to value.take(262_144))) }
    }

    fun allowResend(id: String, paneId: String) {
        change(id) { it.copy(uncertainDrafts = it.uncertainDrafts - it.draftKey(paneId)) }
    }

    fun refresh(id: String, explicit: Boolean = false) {
        val endpoint = endpoints[id] ?: return
        viewModelScope.launch {
            try {
                endpoint.resync()
                change(id) { it.copy(error = null) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                change(id) { it.copy(error = error.description()) }
            }
        }
    }

    fun setHistory(id: String, enabled: Boolean) {
        change(id) { it.copy(history = enabled) }
    }

    fun send(id: String, pane: Pane, key: String? = null) {
        if (key != null) {
            val event = if (key == "ctrl+c") PaneInput.Key(PaneKey.Character('c'.code), modifiers = 2)
                else keyChord(key, 0) ?: return
            input(id, pane.id, listOf(event))
            return
        }
        val client = clients[id] ?: return
        val before = state.value.servers[id] ?: return
        val draftKey = before.draftKey(pane.id)
        val text = before.drafts[draftKey].orEmpty()
        if (pane.id in before.sending || text.isBlank() || before.uncertainDrafts[draftKey] == text) return
        val currentPane = before.snapshot?.panes?.firstOrNull { it.id == pane.id } ?: return
        change(id) { it.copy(sending = it.sending + pane.id, paneErrors = it.paneErrors - pane.id, notice = null) }
        viewModelScope.launch {
            try {
                client.send(currentPane, text)
                if (clients[id] === client) change(id) {
                    it.copy(drafts = if (it.drafts[draftKey] == text) it.drafts - draftKey else it.drafts, notice = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (clients[id] === client) {
                    val uncertain = !error.definitelyRejected()
                    change(id) { it.copy(
                        paneErrors = it.paneErrors + (pane.id to if (uncertain) "${error.description()}. Input may have been delivered; check the pane." else error.description()),
                        uncertainDrafts = if (uncertain) it.uncertainDrafts + (draftKey to text) else it.uncertainDrafts,
                    ) }
                }
            } finally {
                if (clients[id] === client) change(id) { it.copy(sending = it.sending - pane.id) }
            }
        }
    }

    fun input(id: String, paneId: String, events: List<PaneInput>, popup: Boolean = false) {
        val endpoint = endpoints[id] ?: return
        val snapshot = state.value.servers[id]?.snapshot ?: return
        val frame = mutableSurfaces.value[id] ?: return
        if (frame.bootId != snapshot.bootId) return
        val activePopup = frame.popup
        if (activePopup == null && frame.panes.none { it.paneId == paneId }) return
        val target = activePopup?.terminalId ?: paneId
        viewModelScope.launch {
            try {
                endpoint.input(target, events, popup || activePopup != null)
                if (endpoints[id] === endpoint) change(id) { it.copy(paneErrors = it.paneErrors - paneId) }
            }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (endpoints[id] === endpoint) paneError(id, paneId, if (error is IllegalArgumentException) "Not sent: ${error.description()}" else "${error.description()}. Input may have been delivered.")
            }
        }
    }

    fun viewportReady(id: String) {
        val server = state.value.servers[id] ?: return
        if (pendingGeometryClaims[id] != server.paneId || server.paneId == null) return
        pendingGeometryClaims.remove(id)
        val tab = server.tabId ?: return
        navigate(id, "tab.focus", "tab_id", tab)
    }

    fun resize(id: String, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val endpoint = endpoints[id] ?: return
        viewModelScope.launch {
            try { endpoint.resize(columns, rows, cellWidth, cellHeight); endpoint.setActive(foreground && state.value.selectedId == id); viewportReady(id) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                change(id) { it.copy(error = error.description()) }
            }
        }
    }

    fun focus(focused: Boolean) {
        foreground = focused
        endpoints.forEach { (id, endpoint) -> viewModelScope.launch {
            val active = focused && state.value.selectedId == id
            try { endpoint.setActive(active) }
            catch (error: Exception) { if (error is CancellationException) throw error; if (endpoints[id] === endpoint) change(id) { it.copy(error = error.description()) } }
        } }
    }

    fun pasteImage(id: String, bytes: ByteArray, mimeType: String) {
        val endpoint = endpoints[id] ?: return
        val snapshot = state.value.servers[id]?.snapshot ?: return
        val paneId = state.value.servers[id]?.paneId ?: return
        val frame = mutableSurfaces.value[id] ?: return
        if (frame.bootId != snapshot.bootId) return
        val target = frame.popup?.let { GraphicsTarget.Popup(it.terminalId) } ?: GraphicsTarget.Pane(paneId)
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> { paneError(id, paneId, "Unsupported image format"); return }
        }
        viewModelScope.launch {
            try { endpoint.pasteImage(target, extension, bytes) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (endpoints[id] === endpoint) paneError(id, paneId, if (error is IllegalArgumentException) "Not sent: ${error.description()}" else "${error.description()}. Image may have been delivered.")
            }
        }
    }

    fun dismissPaneError(id: String, paneId: String) { change(id) { it.copy(paneErrors = it.paneErrors - paneId) } }

    private fun paneError(id: String, paneId: String, message: String) {
        change(id) { it.copy(paneErrors = it.paneErrors + (paneId to message)) }
    }

    private fun change(id: String, transform: (ServerState) -> ServerState) {
        mutableState.update { state -> state.copy(servers = state.servers + (id to transform(state.servers[id] ?: ServerState()))) }
    }

    override fun onCleared() {
        connections.values.forEach { (ssh, job) -> job.cancel(); ssh.close() }
        endpoints.values.forEach(EndpointSession::close)
    }
}

internal fun ServerState.withSnapshot(snapshot: Snapshot): ServerState {
    val workspace = snapshot.workspaces.firstOrNull { it.id == workspaceId }
        ?: snapshot.workspaces.firstOrNull { it.id == snapshot.focusedWorkspaceId } ?: snapshot.workspaces.firstOrNull()
    val tabs = snapshot.tabs.filter { it.workspaceId == workspace?.id }
    val tab = tabs.firstOrNull { it.id == tabId } ?: tabs.firstOrNull { it.id == workspace?.activeTabId } ?: tabs.firstOrNull()
    val panes = snapshot.panes.filter { it.workspaceId == workspace?.id && it.tabId == tab?.id }
    val pane = panes.firstOrNull { it.id == paneId } ?: panes.firstOrNull()
    return copy(snapshot = snapshot, workspaceId = workspace?.id, tabId = tab?.id, paneId = pane?.id,
        output = output?.takeIf { it.paneId == pane?.id })
}

internal fun Exception.definitelyRejected(): Boolean = this is ApiException && code in setOf(
    "agent_blocked", "agent_not_ready", "agent_not_found", "empty_agent_prompt", "invalid_key", "pane_not_found", "invalid_request", "unsupported_method",
)

internal fun Exception.description(): String = when (this) {
    is TimeoutCancellationException -> "Server response timed out"
    else -> message ?: javaClass.simpleName
}
