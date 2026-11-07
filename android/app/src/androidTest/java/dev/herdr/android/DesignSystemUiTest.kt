package dev.herdr.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real production controls with local presentation data; no SSH connection is needed. */
class DesignSystemUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val panes = listOf(
        Pane("w1:p1", "w1", "w1:t1", label = "Build", agent = "codex", cwd = "~/projects/herdr", status = "working"),
        Pane("w1:p2", "w1", "w1:t1", label = "Review", agent = "claude", cwd = "~/projects/herdr", status = "blocked"),
    )
    private val server = ServerState(connected = true, workspaceId = "w1", tabId = "w1:t1", paneId = panes.first().id,
        snapshot = Snapshot(version = "0.9.3", bootId = "design-fixture", revision = 1,
            workspaces = listOf(Workspace("w1", "Herdr Android", activeTabId = "w1:t1")),
            tabs = listOf(Tab("w1:t1", "w1", "Main")), panes = panes))

    @Test fun singleTabWorkspaceKeepsTabCreationDiscoverable() {
        var requested: WorkspaceAction? = null
        showWorkspace(onAction = { requested = it })
        capture("design-workspace")
        ui.onNodeWithContentDescription("Tabs").assertIsDisplayed().performClick()
        ui.onNodeWithText("New tab").assertIsDisplayed()
        capture("design-tabs")
        ui.onNodeWithText("New tab").performClick()
        ui.runOnIdle { assertEquals(WorkspaceAction.CREATE_TAB, requested) }
    }

    @Test fun paneActionsRemainSearchableAndPreventStartingAnAgentOverAnother() {
        var requested: WorkspaceAction? = null
        showWorkspace(dark = true, onAction = { requested = it })
        ui.onNodeWithContentDescription("Pane actions").performClick()
        capture("design-paneactions")
        ui.onNode(hasSetTextAction()).performTextReplacement("Start agent")
        ui.onNode(hasText("Start agent") and !hasSetTextAction()).assertIsNotEnabled()
        ui.runOnIdle { assertEquals(null, requested) }
        ui.onNode(hasSetTextAction()).performTextReplacement("Resize")
        ui.onNodeWithText("Resize pane").assertIsDisplayed().assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(WorkspaceAction.RESIZE_PANE, requested) }
    }

    @Test fun largeTextOverviewProvidesActionsForTheChosenPane() {
        var requested: Pair<String, WorkspaceAction>? = null
        showWorkspace(dark = true, fontScale = 2f, onPaneAction = { pane, action -> requested = pane to action })
        ui.onNodeWithContentDescription("Actions for Review").assertIsDisplayed()
        capture("design-overview")
        ui.onNodeWithContentDescription("Actions for Review").performClick()
        ui.onNodeWithText("Split down").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals(panes[1].id to WorkspaceAction.SPLIT_DOWN, requested) }
    }

    @Test fun closingPaneRequiresConfirmationAndKeepsItsOriginalTarget() {
        val currentServer = mutableStateOf(server)
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var completed = false
        ui.runOnUiThread {
            ui.activity.setContent {
                HerdrTheme(dark = false) {
                    OperationDialog(WorkspaceAction.CLOSE_PANE, currentServer.value,
                        request = { method, params -> requests += method to params; buildJsonObject {} },
                        onDismiss = {}, onSuccess = { completed = true })
                }
            }
        }
        val close = ui.onNode(hasText("Close pane") and hasClickAction())
        close.assertIsNotEnabled()
        ui.runOnIdle {
            assertTrue(requests.isEmpty())
            currentServer.value = server.copy(paneId = panes[1].id)
        }
        ui.onNodeWithText("I confirm this action").performClick()
        close.assertIsEnabled().performClick()
        ui.waitUntil { completed }
        ui.runOnIdle {
            assertEquals(1, requests.size)
            assertEquals("pane.close", requests.single().first)
            assertEquals(panes.first().id, requests.single().second.getValue("pane_id").jsonPrimitive.content)
        }
    }

    @Test fun anUncertainCreateResultRequiresCheckingTheServerBeforeRetry() {
        var attempts = 0
        var completed = false
        ui.runOnUiThread {
            ui.activity.setContent {
                HerdrTheme(dark = false) {
                    OperationDialog(WorkspaceAction.CREATE_TAB, server,
                        request = { _, _ ->
                            attempts++
                            if (attempts == 1) throw IOException("Connection interrupted")
                            buildJsonObject {}
                        }, onDismiss = {}, onSuccess = { completed = true })
                }
            }
        }
        val create = ui.onNode(hasText("Create tab") and hasClickAction())
        create.performClick()
        ui.onNodeWithText("I checked the server; allow another action").assertIsDisplayed()
        create.assertIsNotEnabled()
        ui.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(false, completed)
        }
        ui.onNodeWithText("I checked the server; allow another action").performClick()
        create.assertIsEnabled()
        ui.runOnIdle { assertEquals(1, attempts) }
        create.performClick()
        ui.waitUntil { completed }
        ui.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun largeTextSettingsAndServerFormsKeepControlsReachable() {
        val settings = mutableStateOf(ClientSettings(theme = "dark"))
        val showSettings = mutableStateOf(true)
        val profile = ServerProfile(name = "Development", host = "100.64.0.10", username = "developer",
            fingerprint = "SHA256:" + "A".repeat(43))
        var saved: ServerProfile? = null
        ui.runOnUiThread {
            ui.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    HerdrTheme(dark = true) {
                        if (showSettings.value) ClientSettingsDialog(settings.value,
                            update = { settings.value = it }, dismiss = { showSettings.value = false })
                        else ProfileDialog(profile, onDismiss = {}, onSave = { saved = it }, onRemove = {})
                    }
                }
            }
        }
        capture("design-settings-large-text")
        ui.onNodeWithText("Terminal bell").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(false, settings.value.bell) }
        clickReachable("Done")
        capture("design-server-large-text")
        ui.onNodeWithText("Advanced settings").performScrollTo().performClick()
        ui.onNodeWithText("Herdr executable path").performScrollTo().assertIsDisplayed()
        clickReachable("Save changes")
        ui.runOnIdle { assertEquals(profile, saved) }
    }

    private fun clickReachable(label: String) {
        val button = ui.onNodeWithText(label)
        if (!button.isDisplayed()) button.performScrollTo()
        button.assertIsDisplayed().performClick()
    }

    private fun showWorkspace(
        dark: Boolean = false,
        fontScale: Float = 1f,
        onAction: (WorkspaceAction) -> Unit = {},
        onPaneAction: (String, WorkspaceAction) -> Unit = { _, _ -> },
    ) {
        ui.runOnUiThread {
            ui.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    HerdrTheme(dark) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            WorkspaceScreen(server, "Development", navigationHeader = { Text("Servers") },
                                navigationFooter = { Text("App settings") }, onRefresh = {}, onWorkspace = {},
                                onTab = {}, onPane = {}, onAction = onAction) {
                                Column(Modifier.fillMaxSize()) {
                                    PaneOverview(null, panes, panes.first().id, onSelect = {}, onAction = onPaneAction,
                                        onSplit = { _, _ -> }, modifier = Modifier.weight(1f))
                                    PaneBar(panes, panes.first(), inputOpen = false, overview = true, onSelect = {},
                                        onInput = {}, onOverview = {}, onAction = onAction, onHistory = {}, onImage = {},
                                        onKey = {}, mouseMode = false, onMouse = {}, onMessage = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        // Window/surface presentation is asynchronous to Compose idleness.
        android.os.SystemClock.sleep(350)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            File(ui.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }
}
