package dev.herdr.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.herdr.android.endpoint.SurfacePane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val HISTORY_PAGE_ROWS = 100L

private data class HistoryPoint(val row: Long, val col: Int) {
    fun json() = buildJsonObject { put("row", row); put("col", col) }
}

private data class HistoryRange(val start: HistoryPoint, val end: HistoryPoint, val revision: Long) {
    fun json() = buildJsonObject { put("start", start.json()); put("end", end.json()) }
}

/** Reads server-owned scrollback; the terminal's normal input remains available below this panel. */
@Composable
internal fun HistoryPanel(id: String, paneId: String, model: HerdrViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(id, paneId) { mutableStateOf("") }
    var text by remember(id, paneId) { mutableStateOf("") }
    var busy by remember(id, paneId) { mutableStateOf(false) }
    var error by remember(id, paneId) { mutableStateOf<String?>(null) }
    var firstRow by remember(id, paneId) { mutableStateOf(0L) }
    var lastRow by remember(id, paneId) { mutableStateOf(0L) }
    var match by remember(id, paneId) { mutableStateOf<HistoryRange?>(null) }
    var searchedQuery by remember(id, paneId) { mutableStateOf("") }
    var matchLabel by remember(id, paneId) { mutableStateOf<String?>(null) }
    var actionsOpen by remember(id, paneId) { mutableStateOf(false) }

    fun surface(): SurfacePane {
        val frame = model.surfaces.value[id] ?: error("No terminal frame available")
        val snapshot = model.state.value.servers[id]?.snapshot ?: error("No server state available")
        check(frame.bootId == snapshot.bootId && frame.projectionRevision == snapshot.revision) {
            "Waiting for an up-to-date terminal frame"
        }
        return frame.panes.firstOrNull { it.paneId == paneId } ?: error("Pane unavailable")
    }

    suspend fun readRange(range: HistoryRange): String {
        val result = model.request(id, "pane.selection.read", buildJsonObject {
            put("pane_id", paneId)
            put("content_revision", range.revision)
            put("anchor", range.start.json())
            put("cursor", range.end.json())
        })
        check(result["pane_id"]?.jsonPrimitive?.content == paneId) { "Response belongs to another pane" }
        return result["text"]?.jsonPrimitive?.content ?: error("Response contains no text")
    }

    suspend fun page(requestedStart: Long?, current: SurfacePane = surface()) {
        val finalRow = ((current.scroll?.maxOffsetFromBottom ?: 0) +
            (current.scroll?.viewportRows ?: current.innerRect.height.toLong()) - 1).coerceAtLeast(0)
        val start = (requestedStart ?: (finalRow - HISTORY_PAGE_ROWS + 1)).coerceIn(0, finalRow)
        val end = (start + HISTORY_PAGE_ROWS - 1).coerceAtMost(finalRow)
        val range = HistoryRange(HistoryPoint(start, 0), HistoryPoint(end, (current.innerRect.width - 1).coerceAtLeast(0)),
            current.contentRevision)
        val result = readRange(range)
        text = result
        firstRow = start
        lastRow = end
    }

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = if (failure is ApiException && failure.code == "stale_content") {
                    match = null
                    "Output changed. Search again or refresh history."
                } else failure.description()
            } finally { busy = false }
        }
    }

    fun search(backward: Boolean) = perform {
        require(query.isNotEmpty() && query.toByteArray().size <= 4096) { "Search query must contain 1–4096 bytes" }
        val requestedQuery = query
        val current = surface()
        val previous = match?.takeIf { it.revision == current.contentRevision && searchedQuery == requestedQuery }
        val origin = previous?.start ?: HistoryPoint(if (backward) lastRow else 0, 0)
        val response = model.request(id, "pane.copy_search", buildJsonObject {
            put("pane_id", paneId)
            put("query", requestedQuery)
            put("direction", if (backward) "backward" else "forward")
            put("cursor", origin.json())
            put("content_revision", current.contentRevision)
            previous?.let { put("previous", it.json()) }
        })
        check(response["pane_id"]?.jsonPrimitive?.content == paneId &&
            response["content_revision"]?.jsonPrimitive?.longOrNull == current.contentRevision) { "Response belongs to a different text revision" }
        val total = response["total"]?.jsonPrimitive?.longOrNull ?: error("Missing match count")
        val index = response["current"]?.jsonPrimitive?.intOrNull
        val selected = index?.let { response["matches"]?.jsonArray?.getOrNull(it)?.jsonObject }
        val range = selected?.let { HistoryRange(it.getValue("start").jsonObject.point(),
            it.getValue("end").jsonObject.point(), current.contentRevision) }
        if (query == requestedQuery) {
            searchedQuery = requestedQuery
            match = range
            val global = response["current_global"]?.jsonPrimitive?.longOrNull
            matchLabel = if (global == null) "Matches: $total" else "${global + 1} of $total"
            if (range != null) page((range.start.row - 2).coerceAtLeast(0), current)
        }
    }

    LaunchedEffect(id, paneId) { perform { page(null) } }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(query, { query = it; match = null; matchLabel = null },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
            label = { Text("Search all server history") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; match = null; matchLabel = null }) {
                Icon(Icons.Default.Close, "Clear search")
            } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotEmpty()) search(false) }))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !busy && query.isNotEmpty(), onClick = { search(true) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous match")
            }
            IconButton(enabled = !busy && query.isNotEmpty(), onClick = { search(false) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next match")
            }
            Text(matchLabel ?: "Select text to copy", Modifier.weight(1f).padding(horizontal = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { actionsOpen = true }) { Icon(Icons.Default.MoreVert, "History actions") }
                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                    DropdownMenuItem(text = { Text("Copy match") }, enabled = !busy && match != null, onClick = {
                        actionsOpen = false
                        perform {
                            val selected = match ?: return@perform
                            val copied = readRange(selected)
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Herdr", copied))
                        }
                    })
                    DropdownMenuItem(text = { Text("Copy line") }, enabled = !busy && match != null, onClick = {
                        actionsOpen = false
                        perform {
                            val selected = match ?: return@perform
                            val response = model.request(id, "pane.copy_motion", buildJsonObject {
                                put("pane_id", paneId)
                                put("cursor", selected.start.json())
                                put("motion", "line_end")
                                put("content_revision", selected.revision)
                            })
                            check(response["pane_id"]?.jsonPrimitive?.content == paneId &&
                                response["content_revision"]?.jsonPrimitive?.longOrNull == selected.revision) { "Response belongs to a different text revision" }
                            val end = response.getValue("cursor").jsonObject.point()
                            val copied = readRange(HistoryRange(HistoryPoint(selected.start.row, 0), end, selected.revision))
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Herdr", copied))
                        }
                    })
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { MessageBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(12.dp)) {
                item { Text(text, fontFamily = FontFamily.Monospace, softWrap = false) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy && firstRow > 0,
                onClick = { perform { page((firstRow - HISTORY_PAGE_ROWS).coerceAtLeast(0)) } }) { Text("Earlier") }
            TextButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy,
                onClick = { perform { page(lastRow + 1) } }) { Text("Later") }
            TextButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy,
                onClick = { perform { page(null) } }) { Text("Latest") }
        }
        Text("Showing lines ${firstRow + 1}–${lastRow + 1}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun JsonObject.point() = HistoryPoint(
    getValue("row").jsonPrimitive.longOrNull ?: error("Invalid row"),
    getValue("col").jsonPrimitive.intOrNull ?: error("Invalid column"),
)
