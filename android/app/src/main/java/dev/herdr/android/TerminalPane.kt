package dev.herdr.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey
import dev.herdr.android.terminal.TerminalView
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalPane(id: String, server: ServerState, model: HerdrViewModel, singlePane: Boolean = true, onAction: (WorkspaceAction) -> Unit = {}, onPaneAction: (String, WorkspaceAction) -> Unit) {
    val frames by model.surfaces.collectAsStateWithLifecycle()
    val pane = server.snapshot?.panes?.firstOrNull { it.id == server.paneId }
    val frame = frames[id]?.takeIf { candidate ->
        candidate.bootId == server.snapshot?.bootId && pane != null && pane.tabId == server.tabId &&
            candidate.panes.any { it.paneId == pane.id }
    }
    var terminal by remember(id) { mutableStateOf<TerminalView?>(null) }
    var keyChordOpen by rememberSaveable(id, pane?.id) { mutableStateOf(false) }
    var inputOpen by rememberSaveable(id) { mutableStateOf(false) }
    var overview by rememberSaveable(id) { mutableStateOf(false) }
    var composer by rememberSaveable(id, pane?.id) { mutableStateOf(false) }
    var touchMouseMode by rememberSaveable(id, pane?.id) { mutableStateOf(false) }
    var modifiers by remember(id, pane?.id) { mutableStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    val composerFocus = remember { FocusRequester() }
    var sawIme by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun closeInput() { inputOpen = false; composer = false; modifiers = 0; keyboard?.hide(); focus.clearFocus() }
    BackHandler(inputOpen || composer || overview || server.history) {
        when { inputOpen || composer -> closeInput(); overview -> overview = false; else -> model.setHistory(id, false) }
    }
    LaunchedEffect(pane?.id) { closeInput() }
    LaunchedEffect(imeVisible) {
        if (imeVisible) sawIme = true
        else if (sawIme) { inputOpen = false; modifiers = 0; sawIme = false }
    }
    LaunchedEffect(inputOpen, terminal) { if (inputOpen && !composer) terminal?.showKeyboard() }
    LaunchedEffect(composer) {
        if (composer && pane?.status != "blocked") {
            composerFocus.requestFocus()
            // Let TextField establish its platform input session before requesting the IME.
            androidx.compose.runtime.withFrameNanos { }
            keyboard?.show()
        }
    }
    val settings by model.settings.collectAsStateWithLifecycle()
    val fontSize = settings.fontSize
    var link by remember { mutableStateOf<String?>(null) }
    var localError by remember(id, server.snapshot?.bootId, server.tabId) { mutableStateOf<String?>(null) }
    var graphicsWarning by remember(id, server.snapshot?.bootId, server.tabId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pane?.id) { localError = null }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollRequests = remember(id, server.snapshot?.bootId, server.tabId) { PendingScroll() }
    LaunchedEffect(scrollRequests) {
        for (signal in scrollRequests.wakeups) {
            while (scrollRequests.pending.isNotEmpty()) {
                val (paneId, offset) = scrollRequests.pending.entries.first().let { it.key to it.value }
                scrollRequests.pending.remove(paneId)
                scrollRequests.inFlight = paneId
                try {
                    val response = model.request(id, "pane.scroll", buildJsonObject {
                        put("pane_id", paneId); put("offset_from_bottom", offset)
                    })
                    if (paneId !in scrollRequests.pending) {
                        val accepted = ((response["pane"] as? JsonObject)?.get("scroll") as? JsonObject)
                            ?.get("offset_from_bottom")?.jsonPrimitive?.longOrNull ?: offset
                        if (model.surfaces.value[id]?.panes?.firstOrNull { it.paneId == paneId }
                                ?.scroll?.offsetFromBottom == accepted) scrollRequests.targets.remove(paneId)
                        else scrollRequests.targets[paneId] = accepted
                    }
                    localError = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    scrollRequests.pending.remove(paneId)
                    scrollRequests.targets.remove(paneId)
                    localError = error.description()
                } finally { scrollRequests.inFlight = null }
            }
        }
    }
    LaunchedEffect(frame, scrollRequests) {
        frame?.panes?.forEach { surface ->
            if (surface.paneId != scrollRequests.inFlight && surface.paneId !in scrollRequests.pending &&
                surface.scroll?.offsetFromBottom == scrollRequests.targets[surface.paneId]) {
                scrollRequests.targets.remove(surface.paneId)
            }
        }
    }
    LaunchedEffect(terminal, fontSize) { terminal?.setFontSize(fontSize) }
    var imageTarget by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var imagePopup by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var readingImage by remember(id) { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = imageTarget
        val popup = imagePopup
        imageTarget = null
        imagePopup = null
        if (uri != null && target != null) scope.launch {
            readingImage = true
            localError = null
            try {
                val image = withContext(Dispatchers.IO) {
                    val mimeType = context.contentResolver.getType(uri)?.lowercase()
                    require(mimeType in setOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
                        "Choose a PNG, JPEG, GIF or WebP image"
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 16 * 1024 * 1024) { "Image exceeds 16 MiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().also { require(it.isNotEmpty()) { "Empty image file" } }
                    } ?: error("Could not open image")
                    bytes to requireNotNull(mimeType)
                }
                val current = model.state.value.servers[id]
                require(current?.connected == true && current.paneId?.let(current::draftKey) == target &&
                    model.surfaces.value[id]?.popup?.terminalId == popup) {
                    "Pane changed. Choose the image again for the intended pane."
                }
                model.pasteImage(id, image.first, image.second)
                localError = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                localError = error.description()
            } finally { readingImage = false }
        }
    }
    if (pane == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Create a workspace or tab", style = MaterialTheme.typography.titleMedium)
            Text("Workspaces, tabs and panes are created on the selected server.")
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                TerminalView(ctx).apply {
                    setFontSize(fontSize)
                    onResize = { cols, rows, cw, ch -> model.resize(id, cols, rows, cw, ch) }
                }
            })
        }
        return
    }
    val draftKey = server.draftKey(pane.id)
    val draft = server.drafts[draftKey].orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val imeHeight = (WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    // Keep PTY geometry stable while the IME, input controls or transient notices take screen space.
    val measuredHeight = (with(density) { (maxHeight - 48.dp).roundToPx() } + imeHeight).coerceAtLeast(1)
    var restingHeight by remember(maxWidth) { mutableStateOf(measuredHeight) }
    if (!imeVisible && !inputOpen && !composer) restingHeight = measuredHeight
    val stableHeight = if (imeVisible || inputOpen || composer) restingHeight else measuredHeight
    Column(Modifier.fillMaxSize()) {
        if (server.snapshot?.tabs?.find { it.id == server.tabId }?.zoomed == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoomed on server", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onAction(WorkspaceAction.ZOOM) }) { Text("Restore for everyone") }
            }
        }
        val panes = server.snapshot?.panes.orEmpty().filter { it.tabId == server.tabId }
        val split: (dev.herdr.android.endpoint.SurfaceSplit, Float) -> Unit = { boundary, ratio -> scope.launch {
            try {
                model.request(id, "layout.set_split_ratio", buildJsonObject {
                    put("tab_id", pane.tabId); put("ratio", ratio); putJsonArray("path") { boundary.path.forEach { add(it) } }
                }); localError = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                localError = error.description()
            }
        }; Unit }
        if (overview) PaneOverview(frame?.takeIf { it.bootId == server.snapshot?.bootId && it.projectionRevision == server.snapshot?.revision }, panes, pane.id,
            { model.selectPane(id, it); overview = false },
            onPaneAction, split, Modifier.weight(1f))
        else if (server.history) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("History · ${pane.displayName}", Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                TextButton(onClick = { model.setHistory(id, false) }) { Text("Close") }
            }
            androidx.compose.runtime.key(id, pane.id) { HistoryPanel(id, pane.id, model, Modifier.weight(1f)) }
        } else Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            androidx.compose.runtime.key(id) { AndroidView(
            modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                val targetHeight = if (frame?.popup != null) constraints.maxHeight else stableHeight
                val placeable = measurable.measure(constraints.copy(minHeight = targetHeight, maxHeight = targetHeight))
                val visibleHeight = constraints.maxHeight
                val surfacePane = frame?.panes?.find { it.paneId == pane.id }
                val cursor = frame?.grid?.cursor?.takeIf { it.visible }
                val cursorRow = if (cursor != null && surfacePane != null && cursor.x in surfacePane.innerRect.x until surfacePane.innerRect.x + surfacePane.innerRect.width)
                    cursor.y - surfacePane.innerRect.y + 1 else surfacePane?.innerRect?.height ?: 1
                val rows = surfacePane?.innerRect?.height?.coerceAtLeast(1) ?: 1
                val offset = (cursorRow.toFloat() / rows * targetHeight - visibleHeight).toInt().coerceIn(0, (targetHeight - visibleHeight).coerceAtLeast(0))
                layout(constraints.maxWidth, visibleHeight) { placeable.place(0, -offset) }
            },
            factory = { ctx -> TerminalView(ctx).apply {
                terminal = this
                onViewportReady = { model.viewportReady(id) }
                onResize = { columns, rows, cellWidth, cellHeight -> model.resize(id, columns, rows, cellWidth, cellHeight) }
                onLink = { link = it }
                setFontSize(fontSize)
            } },
            update = { view ->
                view.onKeyboardRequested = { composer = false; inputOpen = true }
                view.onInput = { target, events, popup ->
                    val outgoing = withOneShotModifiers(events, modifiers)
                    if (outgoing != events) modifiers = 0
                    model.input(id, target, outgoing, popup)
                }
                view.onSelect = { model.selectPane(id, it) }
                view.onGestureStart = { target ->
                    if (scrollRequests.inFlight != target && target !in scrollRequests.pending) scrollRequests.targets.remove(target)
                }
                view.onFontScale = { scale -> model.updateSettings(settings.copy(fontSize = (fontSize * scale).coerceIn(8f, 24f))) }
                view.touchMouseMode = touchMouseMode
                view.onGraphicsWarning = { graphicsWarning = it }
                view.update(frame, pane.id, singlePane)
                view.onScroll = { paneId, delta ->
                    frame?.panes?.firstOrNull { it.paneId == paneId }?.scroll?.let { scroll ->
                        val offset = ((scrollRequests.targets[paneId] ?: scroll.offsetFromBottom) + delta)
                            .coerceIn(0, scroll.maxOffsetFromBottom)
                        scrollRequests.targets[paneId] = offset
                        scrollRequests.pending[paneId] = offset
                        scrollRequests.wakeups.trySend(Unit)
                    }
                }
                view.onSplit = split
            },
        ) }
            val scroll = frame?.panes?.find { it.paneId == pane.id }?.scroll
            if (scroll != null && scroll.offsetFromBottom > 0) {
                TextButton(onClick = {
                    scrollRequests.targets[pane.id] = 0L
                    scrollRequests.pending[pane.id] = 0L
                    scrollRequests.wakeups.trySend(Unit)
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp), colors = ButtonDefaults.textButtonColors(containerColor = TerminalOverlayBackground, contentColor = TerminalOverlayForeground)) { Text("Latest") }
            }
        }
        val error = localError ?: server.paneErrors[pane.id]
        error?.let { message ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { localError = null; model.dismissPaneError(id, pane.id) }) { Text("Dismiss") }
            }
        }
        graphicsWarning?.let { message ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { model.refresh(id, explicit = true) }) { Text("Refresh") }
            }
        }
        if (pane.status == "blocked" && !imeVisible) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Needs input", Modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            listOf("Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "1" to "1", "2" to "2", "3" to "3").forEach { (label, key) ->
                TextButton(modifier = Modifier.semantics { contentDescription = key }, onClick = { model.send(id, pane, key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text(label) }
            }
        }
        if (!composer) PaneBar(panes, pane, imeVisible && terminal?.hasFocus() == true, overview,
            { model.selectPane(id, it) },
            { if (imeVisible && terminal?.hasFocus() == true) closeInput() else { overview = false; model.setHistory(id, false); composer = false; inputOpen = true; terminal?.showKeyboard() } },
            { closeInput(); model.setHistory(id, false); overview = !overview }, onAction,
            { closeInput(); overview = false; model.setHistory(id, !server.history) },
            {
                imageTarget = server.draftKey(pane.id)
                imagePopup = frame?.popup?.terminalId
                try { imagePicker.launch("image/*") } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    imageTarget = null; localError = error.description()
                }
            }, { keyChordOpen = true }, touchMouseMode, { touchMouseMode = !touchMouseMode },
            { closeInput(); overview = false; model.setHistory(id, false); composer = true })
        if (imeVisible && terminal?.hasFocus() == true && !composer) Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("Esc" to "esc", "⇧Tab" to "backtab", "Tab" to "tab").forEach { (label, key) ->
                TextButton(modifier = Modifier.semantics { contentDescription = key }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { keyChord(key, modifiers)?.let { model.input(id, pane.id, listOf(it)) }; modifiers = 0 }) { Text(label) }
            }
            listOf("Ctrl" to 2, "Alt" to 4).forEach { (label, bit) ->
                FilterChip(modifiers and bit != 0, { modifiers = modifiers xor bit }, label = { Text(label) })
            }
            listOf("↑" to "up", "↓" to "down", "←" to "left", "→" to "right", "^C" to "ctrl+c").forEach { (label, key) ->
                TextButton(modifier = Modifier.semantics { contentDescription = key }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { if (key == "ctrl+c") model.send(id, pane, key) else keyChord(key, modifiers)?.let { model.input(id, pane.id, listOf(it)) }; modifiers = 0 }) { Text(label) }
            }
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { model.input(id, pane.id, listOf(PaneInput.Key(PaneKey.Special.PageUp, modifiers))); modifiers = 0 }) { Text("PgUp") }
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { model.input(id, pane.id, listOf(PaneInput.Key(PaneKey.Special.PageDown, modifiers))); modifiers = 0 }) { Text("PgDn") }
        }
        if (composer) androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Message · ${pane.displayName}", Modifier.weight(1f).padding(start = 12.dp), maxLines = 1,
                    style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { closeInput() }) { Text("Close") }
            }
            if (pane.status == "blocked") Text("Agent needs input — answer with keys", Modifier.padding(12.dp))
            else Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { model.draft(id, pane.id, it) }, Modifier.weight(1f).focusRequester(composerFocus),
                    label = { Text("Message to agent") }, maxLines = 5)
                Button(onClick = { model.send(id, pane) }, enabled = draft.isNotBlank() && pane.id !in server.sending && server.uncertainDrafts[draftKey] != draft) { Text("Send") }
            }
        }
        }
        if (server.uncertainDrafts[draftKey] == draft) TextButton(onClick = { model.allowResend(id, pane.id) }) {
            Text("I checked the pane — allow resend")
        }
        server.notice?.takeIf { server.snapshot?.tabs?.find { it.id == server.tabId }?.zoomed == true }?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall) }
    }
    }
    if (keyChordOpen) KeyChordDialog({ model.input(id, pane.id, listOf(it)) }, { keyChordOpen = false })
    link?.let { url -> AlertDialog(onDismissRequest = { link = null }, title = { Text("Open link") },
        text = { SelectionContainer { Text(url) } }, confirmButton = {
            TextButton(onClick = {
                try {
                    val uri = Uri.parse(url)
                    require(uri.scheme in setOf("https", "http", "mailto")) { "This link is only available on the server" }
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    localError = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    localError = error.description()
                }
                link = null
            }) { Text("Open") }
        }, dismissButton = { TextButton(onClick = { link = null }) { Text("Cancel") } }) }
}

/** UI-thread state: accumulate gesture deltas locally and keep only the latest queued offset. */
private class PendingScroll {
    val targets = mutableMapOf<String, Long>()
    val pending = linkedMapOf<String, Long>()
    val wakeups = Channel<Unit>(Channel.CONFLATED)
    var inFlight: String? = null
}
