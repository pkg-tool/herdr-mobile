package dev.herdr.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey

@Composable
internal fun KeyChordDialog(send: (PaneInput.Key) -> Unit, dismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    var modifiers by rememberSaveable { mutableStateOf(2) }
    var showSupportedKeys by rememberSaveable { mutableStateOf(false) }
    val key = keyChord(value, modifiers)
    fun submit() { key?.let { send(it); dismiss() } }
    FormDialog(onDismissRequest = dismiss, title = { Text("Send key") }, text = {
        Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send a key combination to the current pane.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("Modifiers")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ctrl" to 2, "Alt" to 4, "Shift" to 1, "Super" to 8).forEach { (label, bit) ->
                    FilterChip(selected = modifiers and bit != 0, onClick = { modifiers = modifiers xor bit },
                        label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Key") }, isError = value.isNotEmpty() && key == null,
                supportingText = { Text(if (value.isNotEmpty() && key == null) "Use one character, a supported key name, or F1–F24."
                    else "For example: c, Enter, or F5") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }))
            TextButton(onClick = { showSupportedKeys = !showSupportedKeys }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showSupportedKeys) "Hide supported keys" else "Supported keys")
            }
            if (showSupportedKeys) Text("Space, Enter, Tab, Esc, Backspace, Delete, Insert, Home, End, PageUp, PageDown, Left, Right, Up, Down, and F1–F24.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(enabled = key != null, onClick = { submit() }) { Text("Send key") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

internal fun keyChord(value: String, modifiers: Int): PaneInput.Key? {
    val key = when {
        value.codePointCount(0, value.length) == 1 -> PaneKey.Character(value.codePointAt(0))
        value.equals("space", ignoreCase = true) -> PaneKey.Character(32)
        value.matches(Regex("[fF]([1-9]|1[0-9]|2[0-4])")) -> PaneKey.Function(value.substring(1).toInt())
        else -> PaneKey.Special.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    } ?: return null
    return PaneInput.Key(key, modifiers)
}
