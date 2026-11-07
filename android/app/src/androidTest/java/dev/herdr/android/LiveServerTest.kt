package dev.herdr.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in contract check against disposable Herdr sessions, never a user's working server. */
@RunWith(AndroidJUnit4::class)
class LiveServerTest {
    @Test fun twoServersKeepIndependentConnectionsAndPersistProfiles() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass disposable SSH server parameters to run this integration test", arguments.containsKey("herdrHost"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = ServerProfile(
            name = "Validation A", host = arguments.getString("herdrHost")!!,
            port = arguments.getString("herdrPort")!!.toInt(),
            username = arguments.getString("herdrUser")!!,
            fingerprint = arguments.getString("herdrFingerprint")!!,
            session = arguments.getString("herdrSessionA")!!,
            executable = arguments.getString("herdrExecutable")!!,
        )
        val second = first.copy(id = first.id + "-second", name = "Validation B", session = arguments.getString("herdrSessionB")!!)
        val keyFile = File(context.filesDir, "validation-key")
        val a = SshConnection(first)
        val b = SshConnection(second)
        val store = ServerStore(context)
        try {
            store.save(first)
            store.save(second)
            val saved = store.profiles.first()
            assertTrue(saved.containsAll(listOf(first, second)))
            a.connect("", keyFile.readBytes())
            b.connect("", keyFile.readBytes())
            val clientA = HerdrClient(a)
            val clientB = HerdrClient(b)
            val paneA = clientA.snapshot().panes.first()
            val paneB = clientB.snapshot().panes.first()
            assertEquals("Fixture sessions should exercise overlapping pane IDs", paneA.id, paneB.id)
            val marker = "HERDR_ANDROID_" + System.nanoTime()
            clientA.send(paneA, "printf '%s\\n' '$marker'")
            withTimeout(10_000) {
                while (!clientA.read(paneA.id).text.contains(marker)) delay(200)
            }
            assertFalse(clientB.read(paneB.id).text.contains(marker))
            val endpoint = EndpointSession(a, this)
            val observerSsh = SshConnection(first)
            val observer = EndpointSession(observerSsh, this)
            try {
                endpoint.start()
                observerSsh.connect("", keyFile.readBytes())
                observer.start()
                val observerWorkspace = observer.snapshots.value!!.focusedWorkspaceId
                endpoint.resize(60, 30, 10, 20)
                endpoint.setActive(true)
                val surface = withTimeout(20_000) { endpoint.surfaces.first { it?.grid?.width == 60 }!! }
                assertTrue(surface.panes.isNotEmpty())
                val created = endpoint.request("workspace.create", buildJsonObject { put("label", "Mobile endpoint"); put("focus", false) })
                val workspaceId = created.getValue("workspace").jsonObject.getValue("workspace_id").jsonPrimitive.content
                val createdPane = created.getValue("root_pane").jsonObject.getValue("pane_id").jsonPrimitive.content
                endpoint.request("workspace.focus", buildJsonObject { put("workspace_id", workspaceId) })
                withTimeout(10_000) { endpoint.snapshots.first { it?.focusedWorkspaceId == workspaceId } }
                // The JSON API projects the server default view, not another client's selection.
                observer.request("client_shell.surface.set", buildJsonObject { put("active", true) })
                assertEquals("Mobile focus must not move the other client", observerWorkspace, observer.snapshots.value!!.focusedWorkspaceId)
                val streamed = "STREAM_" + System.nanoTime()
                endpoint.input(createdPane, listOf(PaneInput.Paste("printf '%s\\n' '$streamed'"), PaneInput.Key(PaneKey.Special.Enter)))
                withTimeout(10_000) {
                    endpoint.surfaces.first { frame -> frame?.grid?.cells?.joinToString("") { it.symbol }?.contains(streamed) == true }
                }
                endpoint.request("workspace.close", buildJsonObject { put("workspace_id", workspaceId) })
            } finally { endpoint.close(); observer.close() }
            assertTrue(clientB.snapshot().panes.isNotEmpty())
            val wrong = SshConnection(first.copy(fingerprint = "SHA256:" + "a".repeat(43)))
            try {
                var rejected = false
                try { wrong.connect("", keyFile.readBytes()) } catch (_: com.jcraft.jsch.JSchException) { rejected = true }
                assertTrue("An untrusted host key must not authenticate", rejected)
            } finally { wrong.close() }
        } finally {
            a.close()
            b.close()
            store.remove(first.id)
            store.remove(second.id)
        }
    }
}
