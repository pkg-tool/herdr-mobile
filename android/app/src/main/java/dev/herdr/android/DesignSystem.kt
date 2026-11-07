package dev.herdr.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Short windows scroll the entire form so a landscape keyboard cannot collapse its fields. */
@Composable
internal fun FormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            // Dialog windows otherwise follow the system theme, ignoring the app's override.
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 320.dp
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.heightIn(max = maxHeight)
                    .then(if (compact) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(if (compact) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.semantics { heading() }) {
                        ProvideTextStyle(if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall) { title() }
                    }
                    Box(if (compact) Modifier else Modifier.weight(1f, fill = false).verticalScroll(scrollState)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

internal data class SheetAction(
    val title: String,
    val detail: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal data class ActionSection(val title: String, val actions: List<SheetAction>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(title: String, subtitle: String?, sections: List<ActionSection>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val searchable = sections.sumOf { it.actions.size } > 12
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null) {
        Column(if (searchable) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close menu") }
            }
            if (searchable) OutlinedTextField(query, { query = it },
                placeholder = { Text("Find action") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
            val filtered = sections.map { section -> section.copy(actions = section.actions.filter {
                query.isBlank() || it.title.contains(query.trim(), true) || section.title.contains(query.trim(), true)
            }) }.filter { it.actions.isNotEmpty() }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = searchable), contentPadding = PaddingValues(bottom = 24.dp)) {
                filtered.forEach { section ->
                    item { SectionLabel(section.title, Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
                    items(section.actions) { action ->
                        val color = when {
                            !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            action.destructive -> MaterialTheme.colorScheme.error
                            action.selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        ListItem(headlineContent = { Text(action.title, color = color, style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = action.detail?.let { detail -> { Text(detail, style = MaterialTheme.typography.bodySmall) } },
                            trailingContent = { if (action.selected) Icon(Icons.Default.Check, null, tint = color) },
                            colors = ListItemDefaults.colors(containerColor = if (action.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 56.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .semantics { selected = action.selected }
                                .clickable(enabled = action.enabled, role = Role.Button) { onDismiss(); action.onClick() }
                        )
                    }
                }
                if (filtered.isEmpty()) item { Text("No matching actions", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
internal fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingToggle(title: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun ConfirmationRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

@Composable
internal fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun MessageBanner(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
