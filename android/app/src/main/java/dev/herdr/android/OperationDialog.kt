package dev.herdr.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun OperationDialog(
    action: WorkspaceAction,
    server: ServerState,
    request: suspend (String, JsonObject) -> JsonObject,
    onDismiss: () -> Unit,
    onSuccess: (JsonObject) -> Unit,
    serverName: String? = null,
) {
    // Freeze identity while editing so an incoming focus update cannot change the mutation target.
    val target = remember(action) { server }
    val targetServer = remember(action) { serverName }
    val formScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var exportedLayout by remember { mutableStateOf<String?>(null) }
    var copiedLayout by remember { mutableStateOf(false) }
    var fields by remember(action) { mutableStateOf(OperationFields(name = when (action) {
        WorkspaceAction.RENAME_WORKSPACE -> target.snapshot?.workspaces?.find { it.id == target.workspaceId }?.label.orEmpty()
        WorkspaceAction.RENAME_TAB -> target.snapshot?.tabs?.find { it.id == target.tabId }?.label.orEmpty()
        WorkspaceAction.RENAME_PANE -> target.snapshot?.panes?.find { it.id == target.paneId }?.label.orEmpty()
        else -> ""
    })) }
    var pluginPlacement by remember { mutableStateOf("tab") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf(emptyList<JsonObject>()) }
    var confirmed by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, OperationRequest>?>(null) }
    val listMethod = when (action) {
        WorkspaceAction.LAYOUT -> "layout.export"
        WorkspaceAction.CLOSE_GROUP -> "workspace.list"
        WorkspaceAction.PLUGIN_LOGS -> "plugin.log.list"
        WorkspaceAction.PLUGINS -> "plugin.list"
        WorkspaceAction.INTEGRATIONS -> "integration.list"
        WorkspaceAction.WORKTREES -> "worktree.list"
        WorkspaceAction.START_AGENT -> "server.agent_manifests"
        else -> null
    }
    val listKey = when (action) {
        WorkspaceAction.CLOSE_GROUP -> "workspaces"
        WorkspaceAction.PLUGIN_LOGS -> "logs"
        WorkspaceAction.PLUGINS -> "plugins"
        WorkspaceAction.INTEGRATIONS -> "integrations"
        WorkspaceAction.WORKTREES -> "worktrees"
        else -> "manifests"
    }
    val listParams = remember(target.workspaceId, action, fields.trustRepository) { buildJsonObject {
        if (action == WorkspaceAction.WORKTREES) { target.workspaceId?.let { put("workspace_id", it) }; put("trust_repository", fields.trustRepository) }
        if (action == WorkspaceAction.LAYOUT) target.tabId?.let { put("tab_id", it) }
        if (action == WorkspaceAction.PLUGIN_LOGS) put("limit", 100)
    } }
    suspend fun load() {
        if (listMethod != null) {
            val result = request(listMethod, listParams).operationResult()
            records = when (action) {
                WorkspaceAction.LAYOUT -> {
                    exportedLayout = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result.getValue("layout").jsonObject)
                    layoutSplits(result.getValue("layout").jsonObject.getValue("root").jsonObject)
                }
                WorkspaceAction.CLOSE_GROUP -> {
                    val repo = target.snapshot?.workspaces?.find { it.id == target.workspaceId }?.worktree?.repoKey
                    result.records(listKey).filter { repo != null && (it["worktree"] as? JsonObject)?.text("repo_key") == repo }
                }
                else -> result.records(listKey)
            }
        }
    }
    LaunchedEffect(action) {
        if (listMethod != null) {
            busy = true
            try { load() } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                error = failure.message ?: "Could not load the list"
            } finally { busy = false }
        }
    }
    fun execute(operation: OperationRequest, keepOpen: Boolean = false) {
        if (busy || uncertain) return
        busy = true; error = null
        scope.launch {
            try {
                val result = request(operation.method, operation.params).operationResult()
                if (keepOpen) {
                    try { load() } catch (failure: Exception) {
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        error = "Action completed, but the list could not be refreshed: ${failure.message}"
                    }
                } else onSuccess(result)
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                uncertain = !failure.definitelyRejected()
                if (uncertain) confirmed = false
                error = if (!uncertain) failure.message
                    else "No response received. The action may have completed; check before retrying. ${failure.message.orEmpty()}"
            } finally { busy = false }
        }
    }
    val destructive = action.isDestructive || action == WorkspaceAction.IMPORT_LAYOUT
    LaunchedEffect(error) { if (error != null) formScroll.animateScrollTo(0) }
    val listOnly = action in setOf(WorkspaceAction.COMMANDS, WorkspaceAction.PLUGINS, WorkspaceAction.INTEGRATIONS, WorkspaceAction.WORKTREES, WorkspaceAction.LAYOUT, WorkspaceAction.PLUGIN_LOGS)
    FormDialog(
        scrollState = formScroll,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(action.title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { MessageBanner(it) }
                if (uncertain) ConfirmationRow("I checked the server; allow another action", false, { uncertain = false })
                targetServer?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!listOnly) {
                    val targetName = when (action) {
                        WorkspaceAction.CREATE_WORKSPACE, WorkspaceAction.LINK_PLUGIN, WorkspaceAction.CLOSE_POPUP -> null
                        WorkspaceAction.RENAME_WORKSPACE, WorkspaceAction.CLOSE_WORKSPACE, WorkspaceAction.CLOSE_GROUP,
                        WorkspaceAction.CREATE_TAB, WorkspaceAction.CREATE_WORKTREE, WorkspaceAction.REORDER_WORKSPACE,
                        WorkspaceAction.REORDER_GROUP, WorkspaceAction.IMPORT_LAYOUT -> target.snapshot?.workspaces?.find { it.id == target.workspaceId }?.label?.let { "Workspace · $it" }
                        WorkspaceAction.RENAME_TAB, WorkspaceAction.CLOSE_TAB, WorkspaceAction.REORDER_TAB -> target.snapshot?.tabs?.find { it.id == target.tabId }?.label?.let { "Tab · $it" }
                        else -> target.snapshot?.panes?.find { it.id == target.paneId }?.displayName?.let { "Pane · $it" }
                    }
                    targetName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    OperationForm(action, fields, { fields = it; if (action == WorkspaceAction.IMPORT_LAYOUT) confirmed = false }, target, records, !busy && !uncertain)
                    if (action == WorkspaceAction.ZOOM) Text("This changes the layout for all clients, including your computer. To view a pane on this phone only, select it instead.")
                    if (destructive) {
                        if (action == WorkspaceAction.CLOSE_GROUP) {
                            Text("All workspaces in this group and their processes will be closed:")
                            records.forEach { Text(it.text("label")) }
                        }
                        Text(if (action == WorkspaceAction.IMPORT_LAYOUT) { if (fields.replaceTab) "The current tab will be replaced and its processes stopped. Layout commands will run on the server." else "A tab will be created. Layout commands will run on the server." } else closeImpact(action, target))
                        ConfirmationRow("I confirm this action", confirmed, { confirmed = it }, enabled = !busy && !uncertain)
                    }
                }
                when (action) {
                    WorkspaceAction.LAYOUT -> {
                        TextButton(enabled = !busy && !uncertain && exportedLayout != null, onClick = { exportedLayout?.let { clipboard.setText(AnnotatedString(it)); copiedLayout = true } }) { Text(if (copiedLayout) "Layout copied" else "Copy layout JSON") }
                        Text("Size of the first pane in each split. Refresh the list if another client changes the layout.")
                        records.forEachIndexed { index, split ->
                            val path = split.getValue("path").jsonArray
                            var ratio by remember(split) { mutableFloatStateOf(split.getValue("ratio").jsonPrimitive.float.coerceIn(0.1f, 0.9f)) }
                            Text("Split ${index + 1}: ${if (split.text("direction") == "right") "left / right" else "top / bottom"} — ${(ratio * 100).toInt()} %")
                            Slider(value = ratio, onValueChange = { ratio = it }, enabled = !busy && !uncertain, valueRange = 0.1f..0.9f)
                            TextButton(enabled = !busy && !uncertain, onClick = { execute(OperationRequest("layout.set_split_ratio", buildJsonObject {
                                target.tabId?.let { put("tab_id", it) }; put("path", path); put("ratio", ratio)
                            }), true) }) { Text("Apply size") }
                        }
                    }
                    WorkspaceAction.COMMANDS -> {
                        if (target.snapshot?.commands.isNullOrEmpty()) Text("No commands available from the server")
                        target.snapshot?.commands.orEmpty().forEach { command ->
                            TextButton(enabled = !busy && !uncertain, onClick = {
                                execute(OperationRequest("command.invoke", buildJsonObject {
                                    put("command_id", command.id)
                                    target.workspaceId?.let { put("workspace_id", it) }; target.tabId?.let { put("tab_id", it) }; target.paneId?.let { put("pane_id", it) }
                                }))
                            }) { Text(command.label) }
                        }
                    }
                    WorkspaceAction.PLUGIN_LOGS -> records.forEach { log ->
                        Text("${log.text("plugin_id")} · ${log.text("action_id")}", style = MaterialTheme.typography.titleSmall)
                        Text(when (log.text("status")) { "running" -> "Running"; "succeeded" -> "Completed"; else -> "Error" })
                        log.text("exit_code").takeIf { it.isNotBlank() }?.let { Text("Exit code: $it") }
                        listOf("stdout", "stderr", "error").forEach { key ->
                            log.text(key).takeIf { it.isNotBlank() }?.let { output ->
                                androidx.compose.foundation.text.selection.SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        HorizontalDivider()
                    }
                    WorkspaceAction.PLUGINS -> {
                        Text("Open plugin panes in")
        Column(Modifier.selectableGroup()) {
                        listOf("tab" to "Tab", "split" to "Split", "popup" to "Popup", "overlay" to "Overlay", "zoomed" to "Zoomed pane").forEach { (key, label) ->
                            ChoiceRow(label, pluginPlacement == key, !busy) { pluginPlacement = key }
                        }
        }
                        records.forEach { plugin ->
                        Text(plugin.text("name"), style = MaterialTheme.typography.titleSmall)
                        plugin.text("description").takeIf { it.isNotBlank() }?.let { Text(it) }
                        val enabled = plugin["enabled"]?.jsonPrimitive?.booleanOrNull == true
                        TextButton(enabled = !busy && !uncertain, onClick = { execute(OperationRequest(if (enabled) "plugin.disable" else "plugin.enable", buildJsonObject { put("plugin_id", plugin.text("plugin_id")) }), true) }) {
                            Text(if (enabled) "Disable" else "Enable")
                        }
                        TextButton(enabled = !busy && !uncertain, onClick = {
                            pending = "Disable and unlink plugin ${plugin.text("name")} from Herdr?" to OperationRequest("plugin.unlink", buildJsonObject { put("plugin_id", plugin.text("plugin_id")) })
                        }) { Text("Unlink plugin") }
                        if (enabled) plugin.records("actions").forEach { entry ->
                            TextButton(enabled = !busy && !uncertain, onClick = { execute(OperationRequest("plugin.action.invoke", buildJsonObject {
                                put("plugin_id", plugin.text("plugin_id")); put("action_id", entry.text("id")); put("context", operationContext(target))
                            })) }) { Text(entry.text("title")) }
                        }
                        if (enabled) plugin.records("panes").forEach { entry ->
                            TextButton(enabled = !busy && !uncertain, onClick = { execute(OperationRequest("plugin.pane.open", buildJsonObject {
                                put("plugin_id", plugin.text("plugin_id")); put("entrypoint", entry.text("id"))
                                put("placement", pluginPlacement); put("focus", true)
                                if (pluginPlacement == "split") { target.paneId?.let { put("target_pane_id", it) }; put("direction", "right") }
                                target.workspaceId?.let { put("workspace_id", it) }
                            })) }) { Text("Open: ${entry.text("title")}") }
                        }
                        (plugin["warnings"] as? JsonArray)?.forEach { warning -> Text(warning.jsonPrimitive.content, color = MaterialTheme.colorScheme.error) }
                        HorizontalDivider()
                    }
                    }
                    WorkspaceAction.INTEGRATIONS -> records.forEach { integration ->
                        Text(integration.text("label"), style = MaterialTheme.typography.titleSmall)
                        Text(when (integration.text("state")) { "current" -> "Installed"; "outdated" -> "Update available"; else -> "Not installed" })
                        val params = buildJsonObject { put("target", integration.text("target")) }
                        TextButton(enabled = !busy && !uncertain && integration["available"]?.jsonPrimitive?.booleanOrNull == true, onClick = { execute(OperationRequest("integration.install", params), true) }) { Text("Install / update") }
                        if (integration.text("state") != "not_installed") TextButton(enabled = !busy && !uncertain, onClick = { pending = "Uninstall ${integration.text("label")} integration?" to OperationRequest("integration.uninstall", params) }) { Text("Remove") }
                        HorizontalDivider()
                    }
                    WorkspaceAction.WORKTREES -> {
                        ConfirmationRow("Trust this repository for this action", fields.trustRepository, { fields = fields.copy(trustRepository = it) }, enabled = !busy && !uncertain)
                        Text("Allows Git to use a repository owned by another user for this action only. Verify the repository first.", style = MaterialTheme.typography.bodySmall)
                        Text("After changing trust, select Refresh list.", style = MaterialTheme.typography.bodySmall)
                        records.forEach { worktree ->
                        Text(worktree.text("label"), style = MaterialTheme.typography.titleSmall)
                        Text(worktree.text("path"), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy && !uncertain, onClick = { execute(OperationRequest("worktree.open", buildJsonObject {
                            target.workspaceId?.let { put("workspace_id", it) }; put("path", worktree.text("path")); put("focus", true); put("trust_repository", fields.trustRepository)
                        })) }) { Text("Open") }
                        val workspaceId = worktree.text("open_workspace_id")
                        if (workspaceId.isNotBlank() && worktree["is_linked_worktree"]?.jsonPrimitive?.booleanOrNull == true) TextButton(enabled = !busy && !uncertain, onClick = {
                            pending = "Remove worktree ${worktree.text("label")}? Its workspace and directory will be deleted. Uncommitted changes prevent removal." to OperationRequest("worktree.remove", buildJsonObject { put("workspace_id", workspaceId); put("force", false); put("trust_repository", fields.trustRepository) })
                        }) { Text("Remove") }
                        HorizontalDivider()
                    }
                    }
                    else -> Unit
                }
                if (listOnly && action != WorkspaceAction.COMMANDS && records.isEmpty() && !busy && error == null) Text(when (action) {
                    WorkspaceAction.PLUGINS -> "No plugins linked. Link a plugin from Workspace actions."
                    WorkspaceAction.PLUGIN_LOGS -> "No plugin activity yet"
                    WorkspaceAction.WORKTREES -> "No worktrees found in this repository"
                    WorkspaceAction.INTEGRATIONS -> "No integrations available"
                    WorkspaceAction.LAYOUT -> "This tab has a single pane. Split it to adjust the layout."
                    else -> "No items available"
                }, style = MaterialTheme.typography.bodyMedium)
                if (listMethod != null) TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    if (action == WorkspaceAction.CLOSE_GROUP) confirmed = false
                    scope.launch {
                        try { load() } catch (failure: Exception) {
                            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                            error = failure.message ?: "Could not refresh the list"
                        } finally { busy = false }
                    }
                }) { Text("Refresh list") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (!listOnly) Button(enabled = !busy && !uncertain && (!destructive || confirmed),
                colors = if (action.isDestructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(), onClick = {
                try {
                    val operation = operationRequest(action, target, fields)
                    if (action == WorkspaceAction.CLOSE_GROUP) {
                        val root = records.firstOrNull { (it["worktree"] as? JsonObject)?.get("is_linked_worktree")?.jsonPrimitive?.booleanOrNull == false }
                        require(root != null) { "Group root workspace not found; refresh the list" }
                        execute(operation.copy(params = buildJsonObject { put("workspace_id", root.text("workspace_id")); put("close_group", true) }))
                    } else execute(operation)
                }
                catch (failure: IllegalArgumentException) { error = failure.message }
            }) { Text(if (busy) "Working…" else when (action) {
                WorkspaceAction.CREATE_WORKSPACE -> "Create workspace"
                WorkspaceAction.CREATE_TAB -> "Create tab"
                WorkspaceAction.CREATE_WORKTREE -> "Create worktree"
                WorkspaceAction.ZOOM -> "Toggle shared zoom"
                else -> action.title
            }) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (listOnly) "Done" else "Cancel") } },
    )
    pending?.let { (message, operation) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirm action") }, text = { Text(message) },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), onClick = { pending = null; execute(operation, true) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}

@Composable
private fun OperationForm(action: WorkspaceAction, fields: OperationFields, change: (OperationFields) -> Unit, server: ServerState, manifests: List<JsonObject>, enabled: Boolean) {
    val creation = action in setOf(WorkspaceAction.CREATE_WORKSPACE, WorkspaceAction.CREATE_TAB, WorkspaceAction.SPLIT_RIGHT, WorkspaceAction.SPLIT_DOWN)
    val named = action in setOf(WorkspaceAction.CREATE_WORKSPACE, WorkspaceAction.CREATE_TAB, WorkspaceAction.CREATE_WORKTREE, WorkspaceAction.RENAME_WORKSPACE, WorkspaceAction.RENAME_TAB, WorkspaceAction.RENAME_PANE, WorkspaceAction.RENAME_AGENT, WorkspaceAction.START_AGENT)
    if (named) OperationField("Name", fields.name, enabled) { change(fields.copy(name = it)) }
    if (creation || action == WorkspaceAction.CREATE_WORKTREE) OperationField("Server directory (optional)", fields.directory, enabled) { change(fields.copy(directory = it)) }
    var advanced by remember(action) { mutableStateOf(false) }
    if (creation || action == WorkspaceAction.START_AGENT) {
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced options") }
        if (advanced && creation) OperationField("Environment: NAME=value, one per line", fields.environment, enabled, false) { change(fields.copy(environment = it)) }
    }
    if (action == WorkspaceAction.CREATE_WORKTREE) {
        OperationField("Branch (optional)", fields.branch, enabled) { change(fields.copy(branch = it)) }
        OperationField("Base ref (optional)", fields.base, enabled) { change(fields.copy(base = it)) }
        OperationField("Worktree path (optional)", fields.path, enabled) { change(fields.copy(path = it)) }
        ConfirmationRow("Trust this repository for this action", fields.trustRepository, { change(fields.copy(trustRepository = it)) }, enabled = enabled)
        Text("Allows Git to use a repository owned by another user for this action only. Verify the repository first.", style = MaterialTheme.typography.bodySmall)
    }
    if (action == WorkspaceAction.START_AGENT) {
        Text("Start an agent in this shell pane.", style = MaterialTheme.typography.bodyMedium)
        OperationField("Agent type", fields.agentKind, enabled) { change(fields.copy(agentKind = it)) }
        Column(Modifier.selectableGroup()) {
        manifests.forEach { manifest -> ChoiceRow(manifest.text("agent"), fields.agentKind == manifest.text("agent"), enabled = enabled) { change(fields.copy(agentKind = manifest.text("agent"))) } }
        }
        if (advanced) OperationField("Arguments, one per line", fields.arguments, enabled, false) { change(fields.copy(arguments = it)) }
    }
    if (action == WorkspaceAction.IMPORT_LAYOUT) {
        OperationField("Tab name (optional)", fields.name, enabled) { change(fields.copy(name = it)) }
        OperationField("Layout JSON", fields.layoutJson, enabled, false) { change(fields.copy(layoutJson = it)) }
        ConfirmationRow("Replace current tab", fields.replaceTab, { change(fields.copy(replaceTab = it)) }, enabled = enabled && server.tabId != null)
    }
    if (action == WorkspaceAction.LINK_PLUGIN) OperationField("Plugin path on server", fields.path, enabled) { change(fields.copy(path = it)) }
    if (action in setOf(WorkspaceAction.REORDER_WORKSPACE, WorkspaceAction.REORDER_GROUP, WorkspaceAction.REORDER_TAB)) {
        val items = if (action != WorkspaceAction.REORDER_TAB) server.snapshot?.workspaces.orEmpty().map { it.id to it.label }
            else server.snapshot?.tabs.orEmpty().filter { it.workspaceId == server.workspaceId }.map { it.id to it.label }
        val selected = if (action != WorkspaceAction.REORDER_TAB) server.workspaceId else server.tabId
        val repo = server.snapshot?.workspaces?.find { it.id == server.workspaceId }?.worktree?.repoKey
        val members = if (action == WorkspaceAction.REORDER_GROUP) server.snapshot?.workspaces.orEmpty().filter { it.worktree?.repoKey == repo }.map { it.id }.toSet() else setOf(selected)
        Text("Move selected item:")
        Column(Modifier.selectableGroup()) {
        items.forEachIndexed { index, (id, label) ->
            if (id !in members) ChoiceRow("Before $label", fields.insertIndex == index, enabled) { change(fields.copy(insertIndex = index)) }
        }
        ChoiceRow("End of list", fields.insertIndex == items.size, enabled) { change(fields.copy(insertIndex = items.size)) }
        }
    }
    if (action == WorkspaceAction.SWAP_PANE) {
        Text("Select a pane to swap with in this tab")
        Column(Modifier.selectableGroup()) {
        server.snapshot?.panes.orEmpty().filter { it.tabId == server.tabId && it.id != server.paneId }.forEach { pane ->
            ChoiceRow(pane.displayName, fields.targetPane == pane.id, enabled) { change(fields.copy(targetPane = pane.id)) }
        }
        }
    }
    if (action == WorkspaceAction.RESIZE_PANE) {
        Column(Modifier.selectableGroup()) {
        listOf("left" to "Left", "right" to "Right", "up" to "Up", "down" to "Down").forEach { (key, label) ->
            ChoiceRow(label, fields.direction == key, enabled) { change(fields.copy(direction = key)) }
        }
        }
        Text("Step: ${(fields.amount * 100).toInt()}%")
        Slider(value = fields.amount, onValueChange = { change(fields.copy(amount = it)) }, enabled = enabled, valueRange = 0.01f..0.5f)
    }
    if (action == WorkspaceAction.EDIT_SCROLLBACK) Text("History opens in the editor configured on the server.")
    if (action == WorkspaceAction.MOVE_PANE) {
        Column(Modifier.selectableGroup()) {
        listOf("new_tab" to "New tab", "new_workspace" to "New workspace", "tab" to "Existing tab").forEach { (key, label) ->
            ChoiceRow(label, fields.destination == key, enabled) { change(fields.copy(destination = key)) }
        }
        }
        if (fields.destination == "tab") Column(Modifier.selectableGroup()) {
        server.snapshot?.tabs.orEmpty().filter { it.id != server.tabId }.forEach { tab ->
            val workspace = server.snapshot?.workspaces?.find { it.id == tab.workspaceId }?.label.orEmpty()
            ChoiceRow("$workspace / ${tab.label}", fields.destinationTab == tab.id, enabled) { change(fields.copy(destinationTab = tab.id)) }
        }
        } else OperationField("Name (optional)", fields.name, enabled) { change(fields.copy(name = it)) }
    }
}

@Composable
private fun OperationField(label: String, value: String, enabled: Boolean, singleLine: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        singleLine = singleLine, minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth())
}

private fun closeImpact(action: WorkspaceAction, server: ServerState): String {
    val panes = server.snapshot?.panes.orEmpty()
    val tabs = server.snapshot?.tabs.orEmpty()
    val workspaceTabs = tabs.filter { it.workspaceId == server.workspaceId }
    val tabPanes = panes.filter { it.tabId == server.tabId }
    val affected = when (action) {
        WorkspaceAction.CLOSE_WORKSPACE -> panes.filter { it.workspaceId == server.workspaceId }
        WorkspaceAction.CLOSE_TAB -> tabPanes
        WorkspaceAction.CLOSE_PANE -> panes.filter { it.id == server.paneId }
        else -> emptyList()
    }
    val agents = affected.mapNotNull { pane -> pane.agent?.let { pane.displayName } }
    return buildString {
        append(when (action) {
            WorkspaceAction.CLEAR_PANE -> "Clears this terminal’s contents for all connected devices. The process keeps running."
            WorkspaceAction.CLOSE_POPUP -> "Closes the server popup for all connected devices."
            WorkspaceAction.CLOSE_GROUP -> "Stops every pane and running process in the workspaces listed above, for all connected devices."
            else -> "Closes ${affected.size} ${if (affected.size == 1) "pane" else "panes"} and stops their processes for all connected devices."
        })
        if (action == WorkspaceAction.CLOSE_PANE && tabPanes.size == 1) append(" This is the last pane in its tab; the tab closes too.")
        if ((action == WorkspaceAction.CLOSE_TAB || action == WorkspaceAction.CLOSE_PANE && tabPanes.size == 1) && workspaceTabs.size == 1) {
            append(" The workspace also closes because this is its last tab.")
        }
        if (agents.isNotEmpty()) append(" Running agents: ${agents.joinToString() }.")
    }
}
