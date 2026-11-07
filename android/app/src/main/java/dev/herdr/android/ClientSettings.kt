package dev.herdr.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
data class ClientSettings(
    val theme: String = "system",
    val fontSize: Float = 13f,
    val notifications: Boolean = true,
    val bell: Boolean = true,
)

@Composable
internal fun ClientSettingsDialog(settings: ClientSettings, update: (ClientSettings) -> Unit, dismiss: () -> Unit) {
    var helpOpen by remember { mutableStateOf(false) }
    FormDialog(onDismissRequest = dismiss, title = { Text("App settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Appearance")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(selected = settings.theme == value, onClick = { update(settings.copy(theme = value)) }, label = { Text(label) })
                }
            }
            HorizontalDivider()
            SectionLabel("Terminal")
            Text("Text size · ${settings.fontSize.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("$ herdr\nReady for your next task", Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * 1.4f).sp)
            }
            Slider(settings.fontSize, { update(settings.copy(fontSize = it)) }, valueRange = 8f..28f, steps = 19,
                modifier = Modifier.semantics { contentDescription = "Terminal text size" })
            HorizontalDivider()
            SectionLabel("Notifications")
            SettingToggle(title = "Server notifications", description = "Show server notices while the app is open.",
                checked = settings.notifications, onCheckedChange = { update(settings.copy(notifications = it)) })
            SettingToggle(title = "Terminal bell", description = "Show a notice when the terminal rings.",
                checked = settings.bell, onCheckedChange = { update(settings.copy(bell = it)) })
            HorizontalDivider()
            SectionLabel("Security")
            Text("Saved passwords are encrypted with a key held in Android Keystore. Strong biometric confirmation is required to save or unlock them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Private keys are used only to sign in and are never saved. Message drafts are kept in memory. Forget a saved password from the server’s connection dialog.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            TextButton(onClick = { helpOpen = !helpOpen }, modifier = Modifier.fillMaxWidth()) {
                Text(if (helpOpen) "Hide controls" else "Controls and gestures")
            }
            if (helpOpen) {
                HelpEntry("Navigate", "Open the navigation menu for servers, workspaces and agents. Switch tabs at the top and panes using the pane bar.")
                HelpEntry("Read and select", "Swipe vertically to scroll. Pinch to change text size. Hold to copy, paste or open a link.")
                HelpEntry("Type or send a message", "Terminal opens live keyboard input. Message keeps a separate draft until you tap Send. Ctrl and Alt apply to the next key; other combinations are under Pane actions → Send key.")
                HelpEntry("Arrange panes", "Open the pane overview to switch panes and resize dividers. Pane actions also offers resize controls. Remote clicks require Touch mouse mode.")
                HelpEntry("Other connections", "Workspace, tab and pane selection is local to this device. Terminal sizes and layout changes are shared with other clients. Disconnecting leaves server processes running.")
            }
        }
    }, confirmButton = { Button(onClick = dismiss) { Text("Done") } })
}

@Composable
private fun HelpEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
