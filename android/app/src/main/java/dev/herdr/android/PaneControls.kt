package dev.herdr.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.endpoint.SurfaceFrame
import dev.herdr.android.endpoint.SurfaceSplit
import dev.herdr.android.endpoint.SplitDirection

private fun paneActionSections(pane: Pane, paneCount: Int, onAction: (WorkspaceAction) -> Unit): List<ActionSection> = listOf(
    "Layout" to listOf(WorkspaceAction.SPLIT_RIGHT, WorkspaceAction.SPLIT_DOWN, WorkspaceAction.RESIZE_PANE,
        WorkspaceAction.MOVE_PANE, WorkspaceAction.SWAP_PANE, WorkspaceAction.ZOOM),
    "Pane and agent" to listOf(WorkspaceAction.RENAME_PANE, WorkspaceAction.START_AGENT, WorkspaceAction.RENAME_AGENT, WorkspaceAction.EDIT_SCROLLBACK),
    "Clear or close" to listOf(WorkspaceAction.CLEAR_PANE, WorkspaceAction.CLOSE_PANE),
).map { (title, actions) -> ActionSection(title, actions.map { action -> SheetAction(action.title,
    detail = if (paneCount < 2 && action in setOf(WorkspaceAction.RESIZE_PANE, WorkspaceAction.SWAP_PANE, WorkspaceAction.ZOOM)) "Only one pane in this tab" else when (action) {
        WorkspaceAction.ZOOM -> "Toggle shared zoom, including on your computer"
        WorkspaceAction.CLOSE_PANE -> "Stop the process running in this pane"
        WorkspaceAction.START_AGENT -> if (pane.agent != null) "An agent is already running" else "Start in this shell"
        else -> null
    }, enabled = when (action) {
        WorkspaceAction.RESIZE_PANE, WorkspaceAction.SWAP_PANE, WorkspaceAction.ZOOM -> paneCount > 1
        WorkspaceAction.START_AGENT -> pane.agent == null
        WorkspaceAction.RENAME_AGENT -> pane.agent != null
        else -> true
    }, destructive = action.isDestructive, onClick = { onAction(action) }) }) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaneBar(panes: List<Pane>, selected: Pane, inputOpen: Boolean, overview: Boolean,
    onSelect: (String) -> Unit, onInput: () -> Unit, onOverview: () -> Unit, onAction: (WorkspaceAction) -> Unit,
    onHistory: () -> Unit, onImage: () -> Unit, onKey: () -> Unit, mouseMode: Boolean, onMouse: () -> Unit, onMessage: () -> Unit) {
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val index = panes.indexOfFirst { it.id == selected.id }
    fun step(delta: Int) { panes.getOrNull(index + delta)?.let { onSelect(it.id) } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pointerInput(panes, selected.id) {
            var distance = 0f
            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                if (distance < -48.dp.toPx()) step(1) else if (distance > 48.dp.toPx()) step(-1)
            }) { change, amount -> change.consume(); distance += amount }
        }, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picker = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                StatusDot(selected.status, Modifier.semantics { contentDescription = statusLabel(selected.status) })
                Text(selected.displayName, Modifier.padding(start = 8.dp).weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ${index + 1}/${panes.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
            if (selected.agent != null) IconButton(onClick = onMessage, enabled = selected.status != "blocked") { Icon(painterResource(R.drawable.ic_message), if (selected.status == "blocked") "Message unavailable: agent needs input" else "Message to agent") }
            IconButton(onClick = onInput) { Icon(painterResource(R.drawable.ic_keyboard), if (inputOpen) "Hide keyboard" else "Terminal", tint = if (inputOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onOverview) { Icon(painterResource(R.drawable.ic_panes), "Pane overview", tint = if (overview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Pane actions") }
        }
    }
    }
    if (menu) ActionSheet("Pane actions", "${selected.displayName} · ${statusLabel(selected.status)}",
        listOf(ActionSection("Input and output", listOf(
            SheetAction("History / copy", detail = "Search and copy server scrollback", onClick = onHistory),
            SheetAction("Paste image", onClick = onImage),
            SheetAction("Send key…", detail = "Shortcuts and special keys", onClick = onKey),
            SheetAction("Touch mouse mode", detail = if (mouseMode) "On · touches go to the terminal app" else "Off · swipe to scroll output", selected = mouseMode, onClick = onMouse),
        ))) + paneActionSections(selected, panes.size, onAction), onDismiss = { menu = false })
    if (picker) ActionSheet("Panes in this tab", "Select a pane to work in", listOf(ActionSection("Panes", panes.map { pane ->
        SheetAction(pane.displayName, detail = "${statusLabel(pane.status)} · ${pane.cwd ?: pane.agent ?: "Shell"}",
            selected = pane.id == selected.id, onClick = { onSelect(pane.id) })
    })), onDismiss = { picker = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaneOverview(frame: SurfaceFrame?, panes: List<Pane>, selected: String, onSelect: (String) -> Unit,
    onAction: (String, WorkspaceAction) -> Unit, onSplit: (SurfaceSplit, Float) -> Unit, modifier: Modifier = Modifier) {
    var menuPane by remember { mutableStateOf<Pane?>(null) }
    var splitMenu by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    var listOverride by remember { mutableStateOf<Boolean?>(null) }
    val layoutMatches = frame != null && frame.panes.map { it.paneId }.toSet() == panes.map { it.id }.toSet()
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
    val cramped = frame?.panes?.any { maxWidth * (it.rect.width.toFloat() / frame.grid.width.coerceAtLeast(1)) < 160.dp } == true
    val listMode = listOverride ?: (fontScale > 1.3f || cramped)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pane layout", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { listOverride = !listMode }) { Text(if (listMode) "Layout view" else "List view") }
        }
        Text(if (listMode) "Select a pane or open its actions" else "Tap to open · drag dividers to resize",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        if (listMode) LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(panes, key = { it.id }) { pane ->
                ListItem(headlineContent = { Text(pane.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(statusLabel(pane.status)) }, leadingContent = { StatusDot(pane.status) },
                    trailingContent = { IconButton(onClick = { menuPane = pane }) { Icon(Icons.Default.MoreVert, "Actions for ${pane.displayName}") } },
                    modifier = Modifier.semantics { this.selected = pane.id == selected }
                    .combinedClickable(onClick = { onSelect(pane.id) }, onLongClick = { menuPane = pane }))
            }
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = maxWidth
            val height = maxHeight
            val density = LocalDensity.current
            val gridWidth = frame?.grid?.width?.coerceAtLeast(1) ?: 1
            val gridHeight = frame?.grid?.height?.coerceAtLeast(1) ?: 1
            val visible = frame?.panes.orEmpty()
            panes.forEachIndexed { index, pane ->
                val area = visible.takeIf { layoutMatches }?.find { it.paneId == pane.id }?.rect
                val x = if (area != null) area.x.toFloat() / gridWidth else 0f
                val y = if (area != null) area.y.toFloat() / gridHeight else index.toFloat() / panes.size
                val w = if (area != null) area.width.toFloat() / gridWidth else 1f
                val h = if (area != null) area.height.toFloat() / gridHeight else 1f / panes.size
                val shape = MaterialTheme.shapes.medium
                Column(Modifier.offset(width * x, height * y).size(width * w, height * h).padding(4.dp)
                    .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .border(if (pane.id == selected) 2.dp else 1.dp,
                        if (pane.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                    .semantics { this.selected = pane.id == selected }
                    .combinedClickable(onClick = { onSelect(pane.id) }, onLongClick = { menuPane = pane })
                    .padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(pane.status)
                        Text(pane.displayName, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { menuPane = pane }) { Icon(Icons.Default.MoreVert, "Actions for ${pane.displayName}") }
                    }
                    Text(statusLabel(pane.status), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val inner = visible.find { it.paneId == pane.id }?.innerRect
                    val preview = remember(frame?.surfaceRevision, pane.id) {
                        if (inner == null || frame == null) "" else (inner.y until inner.y + inner.height).map { row ->
                            frame.grid.cells.subList(row * gridWidth + inner.x, row * gridWidth + inner.x + inner.width)
                                .joinToString("") { it.symbol }.trimEnd()
                        }.lastOrNull { it.isNotBlank() }.orEmpty()
                    }
                    Text(preview, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            frame?.splits?.takeIf { layoutMatches }?.forEachIndexed { splitIndex, split ->
                val horizontal = split.direction == SplitDirection.Horizontal
                var drag by remember(split.path) { mutableFloatStateOf(0f) }
                val extent = if (horizontal) with(density) { width.toPx() } else with(density) { height.toPx() }
                val gridExtent = if (horizontal) gridWidth else gridHeight
                val start = if (horizontal) split.area.x else split.area.y
                val length = if (horizontal) split.area.width else split.area.height
                val initial = (split.position - start).toFloat() / length.coerceAtLeast(1)
                Box(Modifier.offset(if (horizontal) width * (split.position.toFloat() / gridWidth) - 12.dp else width * (split.area.x.toFloat() / gridWidth),
                    if (horizontal) height * (split.area.y.toFloat() / gridHeight) else height * (split.position.toFloat() / gridHeight) - 12.dp)
                    .size(if (horizontal) 24.dp else width * (split.area.width.toFloat() / gridWidth),
                        if (horizontal) height * (split.area.height.toFloat() / gridHeight) else 24.dp)
                    .semantics {
                        contentDescription = "Resize split"
                        stateDescription = "${if (horizontal) "Left/right" else "Top/bottom"} split ${splitIndex + 1}, ${(initial * 100).toInt()} percent"
                        progressBarRangeInfo = ProgressBarRangeInfo(initial, 0.1f..0.9f)
                        setProgress { onSplit(split, it.coerceIn(0.1f, 0.9f)); true }
                    }
                    .pointerInput(split, extent) {
                        detectDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f }, onDragEnd = {
                            onSplit(split, (initial + drag / extent * gridExtent / length.coerceAtLeast(1)).coerceIn(0.1f, 0.9f)); drag = 0f
                        }) { change, amount -> change.consume(); drag += if (horizontal) amount.x else amount.y }
                    }, contentAlignment = Alignment.Center) {
                    if (kotlin.math.abs(drag) > 1f) Text("${((initial + drag / extent * gridExtent / length.coerceAtLeast(1)).coerceIn(0.1f, 0.9f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall)
                    else Box(Modifier.size(if (horizontal) 4.dp else 32.dp, if (horizontal) 32.dp else 4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { splitMenu = true }, modifier = Modifier.weight(1f)) { Text("Add pane") }
            FilledTonalButton(onClick = { onSelect(selected) }, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
    }
    if (splitMenu) ActionSheet("Add pane", panes.find { it.id == selected }?.displayName,
        listOf(ActionSection("Split selected pane", listOf(WorkspaceAction.SPLIT_RIGHT, WorkspaceAction.SPLIT_DOWN).map { action ->
            SheetAction(action.title) { onAction(selected, action) }
        })), onDismiss = { splitMenu = false })
    menuPane?.let { pane -> ActionSheet("Pane actions", "${pane.displayName} · ${statusLabel(pane.status)}",
        paneActionSections(pane, panes.size) { onAction(pane.id, it) }, onDismiss = { menuPane = null }) }
}
