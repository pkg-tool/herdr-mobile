package dev.herdr.android

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt in with disposable server parameters, just like MobileUiTest. */
class HistoryUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchOldScrollbackCopyAndSendImageThroughProductionClient() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires a disposable Herdr SSH session", args.containsKey("herdrHost"))
        val context = ui.activity.applicationContext
        val profile = ServerProfile(name = "History validation", host = args.getString("herdrHost")!!,
            port = args.getString("herdrPort")!!.toInt(), username = args.getString("herdrUser")!!,
            fingerprint = args.getString("herdrFingerprint")!!, session = args.getString("herdrSessionA")!!,
            executable = args.getString("herdrExecutable")!!)
        val store = ServerStore(context)
        runBlocking { store.save(profile) }
        val model = ViewModelProvider(ui.activity)[HerdrViewModel::class.java]
        var tabId: String? = null
        try {
            ui.runOnIdle {
                model.select(profile.id)
                model.connect(profile, "", File(context.filesDir, "validation-key").readBytes())
            }
            ui.waitUntil(20_000) { model.state.value.servers[profile.id]?.snapshot != null && model.surfaces.value[profile.id] != null }
            val workspace = model.state.value.servers.getValue(profile.id).workspaceId!!
            val created = runBlocking { model.request(profile.id, "tab.create", buildJsonObject {
                put("workspace_id", workspace); put("label", "History test"); put("focus", false)
            }) }
            tabId = created.getValue("tab").jsonObject.getValue("tab_id").jsonPrimitive.content
            val paneId = created.getValue("root_pane").jsonObject.getValue("pane_id").jsonPrimitive.content
            ui.runOnIdle { model.created(profile.id, created) }
            ui.waitUntil(15_000) { model.state.value.servers[profile.id]?.paneId == paneId &&
                model.surfaces.value[profile.id]?.panes?.any { it.paneId == paneId } == true }

            val suffix = System.nanoTime().toString(16).takeLast(8)
            val marker = "HISTORY_$suffix"
            val ready = "READY_$suffix"
            // The expanded marker does not occur in the echoed command. The first match is
            // older than the former 500-line client-side history limit.
            sendCommand("printf 'HISTORY_%s\\n' '$suffix'; seq 1 650; printf 'HISTORY_%s\\nREADY_%s\\n' '$suffix' '$suffix'")
            waitForOutput(model, profile.id, paneId, ready)
            ui.onNodeWithContentDescription("Pane actions").performClick()
            ui.onNodeWithText("History / copy").performClick()
            ui.onNodeWithText("Search all server history").performTextInput(marker)
            awaitSearchReady()
            ui.onNodeWithContentDescription("Next match").performClick()
            ui.waitUntil(10_000) { ui.onAllNodes(androidx.compose.ui.test.hasText("1 of 2")).fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("1 of 2").assertIsDisplayed()
            awaitSearchReady()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            ui.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "unchanged")) }
            ui.onNodeWithContentDescription("History actions").performClick()
            ui.onNodeWithText("Copy match").performClick()
            ui.waitUntil(10_000) {
                var copied: String? = null
                ui.runOnIdle { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                copied == marker
            }
            ui.onNodeWithContentDescription("Next match").performClick()
            ui.waitUntil(10_000) { ui.onAllNodes(androidx.compose.ui.test.hasText("2 of 2")).fetchSemanticsNodes().size == 1 }
            awaitSearchReady()
            ui.onNodeWithContentDescription("Previous match").performClick()
            ui.waitUntil(10_000) { ui.onAllNodes(androidx.compose.ui.test.hasText("1 of 2")).fetchSemanticsNodes().size == 1 }
            awaitSearchReady()
            ui.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "unchanged")) }
            ui.onNodeWithContentDescription("History actions").performClick()
            ui.onNodeWithText("Copy line").performClick()
            ui.waitUntil(10_000) {
                var copied: String? = null
                ui.runOnIdle { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trimEnd() }
                copied == marker
            }

            // History is open: typing a shell command must still be possible.
            val imageReady = "IMAGE_READY_$suffix"
            sendCommand("printf 'IMAGE_READY_%s\\n' '$suffix'; read -r image; sha256sum \"\$image\"; rm -- \"\$image\"")
            waitForOutput(model, profile.id, paneId, imageReady)

            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val image = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            bitmap.recycle()
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
            ui.waitUntil(10_000) {
                val snapshot = model.state.value.servers[profile.id]?.snapshot
                val frame = model.surfaces.value[profile.id]
                frame != null && snapshot != null && frame.bootId == snapshot.bootId && frame.projectionRevision == snapshot.revision
            }
            ui.runOnIdle { model.pasteImage(profile.id, image, "image/png") }
            waitForOutput(model, profile.id, paneId, "-clipboard-")
            ui.runOnIdle { terminal().onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("\n", 1) }
            val output = waitForOutput(model, profile.id, paneId, expectedHash)
            assertTrue("The file staged by the real Herdr server must contain the selected PNG bytes", output.contains(expectedHash))
        } finally {
            try {
                tabId?.let { tab -> runBlocking { model.request(profile.id, "tab.close", buildJsonObject { put("tab_id", tab) }) } }
            } finally {
                ui.runOnIdle { model.disconnect(profile.id) }
                runBlocking { store.remove(profile.id) }
            }
        }
    }

    private fun terminal(): dev.herdr.android.terminal.TerminalView {
        fun find(view: android.view.View): dev.herdr.android.terminal.TerminalView? =
            if (view is dev.herdr.android.terminal.TerminalView) view else (view as? android.view.ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
        return requireNotNull(find(ui.activity.window.decorView))
    }

    private fun sendCommand(command: String) {
        ui.onNodeWithContentDescription("Terminal").performClick()
        ui.waitForIdle()
        ui.runOnIdle {
            val input = terminal().onCreateInputConnection(android.view.inputmethod.EditorInfo())
            input.commitText(command, 1)
            input.commitText("\n", 1)
        }
    }

    private fun awaitSearchReady() = ui.waitUntil(10_000) {
        ui.onAllNodes(hasContentDescription("Next match") and isEnabled()).fetchSemanticsNodes().size == 1
    }

    private fun waitForOutput(model: HerdrViewModel, serverId: String, paneId: String, expected: String): String = runBlocking {
        withTimeout(15_000) {
            var output: String
            do {
                val result = model.request(serverId, "pane.read", buildJsonObject {
                    put("pane_id", paneId); put("source", "recent_unwrapped"); put("lines", 200); put("format", "text")
                })
                output = result.getValue("read").jsonObject.getValue("text").jsonPrimitive.content
                if (!output.contains(expected)) delay(100)
            } while (!output.contains(expected))
            output
        }
    }
}
