package dev.herdr.android

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import dev.herdr.android.terminal.TerminalView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses the production Activity, ViewModel, SSH and live server; test sessions only. */
class MobileUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun createTabSplitAndSwitchServers() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("herdrHost"))
        val context = ui.activity.applicationContext
        val profile = ServerProfile(name = "UI Server A", host = args.getString("herdrHost")!!,
            port = args.getString("herdrPort")!!.toInt(), username = args.getString("herdrUser")!!,
            fingerprint = args.getString("herdrFingerprint")!!, session = args.getString("herdrSessionA")!!,
            executable = args.getString("herdrExecutable")!!)
        val second = profile.copy(id = profile.id + "-b", name = "UI Server B", session = args.getString("herdrSessionB")!!)
        val store = ServerStore(context)
        runBlocking { store.save(profile); store.save(second) }
        ui.waitForIdle()
        val model = ViewModelProvider(ui.activity)[HerdrViewModel::class.java]
        try {
            ui.runOnIdle {
                model.select(profile.id)
                model.connect(profile, "", File(context.filesDir, "validation-key").readBytes())
                model.connect(second, "", File(context.filesDir, "validation-key").readBytes())
            }
            ui.waitUntil(20_000) { model.state.value.servers.values.count { it.connected && it.snapshot != null } == 2 }
            val workspace = model.state.value.servers.getValue(profile.id).workspaceId!!
            ui.onNodeWithContentDescription("Workspace actions").performClick()
            ui.onNodeWithText("New tab").performClick()
            val tabName = "Android UI " + System.nanoTime()
            ui.onNodeWithText("Name").performTextInput(tabName)
            ui.onNodeWithText("Create tab").performClick()
            ui.waitUntil(15_000) { model.state.value.servers[profile.id]?.let { s -> s.snapshot?.tabs?.any { it.label == tabName && it.id == s.tabId } } == true }
            ui.onNodeWithText(tabName).assertIsDisplayed()
            val tab = model.state.value.servers.getValue(profile.id).tabId!!
            ui.onNodeWithContentDescription("Pane overview").performClick()
            ui.onNodeWithText("Add pane").performClick()
            ui.onNode(hasText("Split right") and hasClickAction()).performClick()
            ui.onNode(hasText("Split right") and hasClickAction()).performClick()
            ui.waitUntil(15_000) { model.state.value.servers[profile.id]?.snapshot?.panes?.count { it.tabId == tab } == 2 }
            ui.waitUntil(15_000) { model.surfaces.value[profile.id]?.panes?.size == 2 }
            val dividerBefore = requireNotNull(model.surfaces.value[profile.id]).splits.single()
            val ratioBefore = (dividerBefore.position - dividerBefore.area.x).toFloat() / dividerBefore.area.width
            ui.onNodeWithContentDescription("Resize split").performTouchInput {
                down(center); moveTo(center + Offset(120f, 0f), 200); up()
            }
            ui.waitUntil(10_000) {
                val divider = model.surfaces.value[profile.id]?.splits?.singleOrNull()
                divider != null && (divider.position - divider.area.x).toFloat() / divider.area.width > ratioBefore + 0.03f
            }
            ui.onNodeWithText("Done").performClick()
            fun terminal(view: View): TerminalView? = if (view is TerminalView) view else
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { terminal(group.getChildAt(it)) } }
            var cellWidth = 1
            var terminalWidth = 1
            ui.runOnIdle {
                val view = requireNotNull(terminal(ui.activity.window.decorView))
                terminalWidth = view.width
                cellWidth = kotlin.math.ceil(Paint().apply {
                    typeface = Typeface.MONOSPACE
                    textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                        model.settings.value.fontSize, view.resources.displayMetrics)
                }.measureText("M")).toInt()
            }
            ui.waitUntil(15_000) {
                val state = model.state.value.servers.getValue(profile.id)
                val surface = model.surfaces.value[profile.id]?.panes?.find { it.paneId == state.paneId }
                surface != null && kotlin.math.abs(surface.innerRect.width * cellWidth - terminalWidth) <= cellWidth * 2
            }
            val beforeInput = requireNotNull(model.surfaces.value[profile.id]).grid
            ui.onNodeWithContentDescription("Terminal").performClick()
            ui.waitUntil(10_000) { ui.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
            ui.waitForIdle()
            val afterInput = requireNotNull(model.surfaces.value[profile.id]).grid
            assertEquals("Opening the keyboard must not resize shared PTYs", beforeInput.width to beforeInput.height, afterInput.width to afterInput.height)
            ui.onNodeWithContentDescription("Hide keyboard", useUnmergedTree = true).performClick()
            val selectedPane = model.state.value.servers.getValue(profile.id).paneId
            val neighbor = model.state.value.servers.getValue(profile.id).snapshot!!.panes.first { it.tabId == tab && it.id != selectedPane }
            ui.runOnIdle { model.selectPane(profile.id, neighbor.id) }
            ui.waitUntil(5_000) { model.state.value.servers[profile.id]?.paneId == neighbor.id }
            ui.runOnIdle { model.selectPane(profile.id, selectedPane) }
            ui.waitUntil(5_000) { model.state.value.servers[profile.id]?.paneId == selectedPane }
            ui.onNodeWithContentDescription("Workspaces and agents").performClick()
            ui.onNodeWithText("UI Server B").performClick()
            ui.waitUntil(5_000) { model.state.value.selectedId == second.id }
            assertTrue(model.state.value.servers.getValue(second.id).snapshot!!.tabs.none { it.label == tabName })
            ui.onNodeWithText("UI Server A").performClick()
            ui.waitUntil(5_000) { model.state.value.selectedId == profile.id }
            assertEquals(tab, model.state.value.servers.getValue(profile.id).tabId)
            ui.activityRule.scenario.recreate()
            ui.waitForIdle()
            ui.onNodeWithText(tabName).assertIsDisplayed()
            assertEquals(workspace, model.state.value.servers.getValue(profile.id).workspaceId)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, "mobile-ui.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            runBlocking { model.request(profile.id, "tab.close", kotlinx.serialization.json.buildJsonObject {
                put("tab_id", kotlinx.serialization.json.JsonPrimitive(tab))
            }) }
        } finally {
            ui.runOnIdle { model.disconnect(profile.id); model.disconnect(second.id) }
            runBlocking { store.remove(profile.id); store.remove(second.id) }
        }
    }
}
