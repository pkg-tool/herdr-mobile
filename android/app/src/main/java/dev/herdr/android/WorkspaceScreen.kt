package dev.herdr.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class WorkspaceAction(val title: String) {
    CREATE_WORKSPACE("New workspace"), CREATE_WORKTREE("New worktree"), WORKTREES("Worktrees"),
    CREATE_TAB("New tab"), SPLIT_RIGHT("Split right"), SPLIT_DOWN("Split down"),
    RENAME_WORKSPACE("Rename workspace"), RENAME_TAB("Rename tab"),
    RENAME_PANE("Rename pane"), CLOSE_WORKSPACE("Close workspace"),
    CLOSE_TAB("Close tab"), CLOSE_PANE("Close pane"), MOVE_PANE("Move pane"),
    START_AGENT("Start agent"), RENAME_AGENT("Rename agent"), ZOOM("Zoom for all clients"),
    REORDER_GROUP("Reorder worktree group"), IMPORT_LAYOUT("Import layout"), REORDER_WORKSPACE("Reorder workspaces"), REORDER_TAB("Reorder tabs"),
    SWAP_PANE("Swap panes"), RESIZE_PANE("Resize pane"), LAYOUT("Pane layout"),
    CLEAR_PANE("Clear terminal"), EDIT_SCROLLBACK("Edit scrollback"), CLOSE_POPUP("Close popup"),
    LINK_PLUGIN("Link plugin"), PLUGIN_LOGS("Plugin logs"), CLOSE_GROUP("Close worktree group"),
    COMMANDS("Commands"), PLUGINS("Plugins"), INTEGRATIONS("Integrations"),
}

@Composable
internal fun WorkspaceScreen(
    server: ServerState,
    serverName: String,
    navigationHeader: @Composable () -> Unit,
    navigationFooter: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onWorkspace: (String) -> Unit,
    onTab: (String) -> Unit,
    onPane: (String) -> Unit,
    onAction: (WorkspaceAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    val snapshot = server.snapshot ?: return
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer = { scope.launch { drawer.close() }; Unit }
    BackHandler(drawer.isOpen) { closeDrawer() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val navigation: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                navigationHeader()
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    WorkspaceNavigation(snapshot, server.workspaceId, server.paneId,
                        { onWorkspace(it); closeDrawer() }, { onPane(it); closeDrawer() },
                        { closeDrawer(); onAction(WorkspaceAction.CREATE_WORKSPACE) })
                }
                HorizontalDivider()
                navigationFooter()
            }
        }
        val main: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                WorkspaceToolbar(server, serverName, !wide, { scope.launch { drawer.open() } }, onRefresh, onTab, onAction)
                Box(Modifier.weight(1f).fillMaxWidth()) { content(!wide) }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(264.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) { navigation() }
            VerticalDivider()
            Box(Modifier.weight(1f)) { main() }
        } else ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen,
            drawerContent = { ModalDrawerSheet(Modifier.width(304.dp)) { navigation() } }, content = main)
    }
}

@Composable
private fun WorkspaceNavigation(
    snapshot: Snapshot,
    workspaceId: String?,
    paneId: String?,
    onWorkspace: (String) -> Unit,
    onAgent: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var waitingOnly by remember(snapshot.bootId) { mutableStateOf(false) }
    var agentQuery by remember(snapshot.bootId) { mutableStateOf("") }
    val agentSort = "attention"
    var collapsedGroups by remember(snapshot.bootId) { mutableStateOf(emptySet<String>()) }
    val groups = snapshot.workspaces.groupBy { it.worktree?.repoKey }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Workspaces", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "New workspace") }
            }
        }
        groups.forEach { (repo, workspaces) ->
            if (repo != null) item(key = "repo:$repo") {
                TextButton(onClick = { collapsedGroups = if (repo in collapsedGroups) collapsedGroups - repo else collapsedGroups + repo }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (repo in collapsedGroups) "Collapsed" else "Expanded" }) {
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.rotate(if (repo in collapsedGroups) -90f else 0f))
                    Text("${workspaces.first().worktree!!.repoName} (${workspaces.size})",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            items(if (repo in collapsedGroups) emptyList() else workspaces, key = { "workspace:${it.id}" }) { workspace ->
                NavigationDrawerItem(label = { Text(workspace.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = workspace.id == workspaceId, onClick = { onWorkspace(workspace.id) },
                    icon = { StatusDot(workspace.status, Modifier.semantics { contentDescription = statusLabel(workspace.status) }) },
                    badge = { Text(snapshot.panes.count { it.workspaceId == workspace.id }.toString()) })
            }
        }
        if (snapshot.workspaces.isEmpty()) item { Text("No workspaces", Modifier.padding(16.dp)) }
        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Agents", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.panes.count { it.agent != null } > 10) OutlinedTextField(value = agentQuery, onValueChange = { agentQuery = it }, label = { Text("Find agent or workspace") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            FilterChip(waitingOnly, { waitingOnly = !waitingOnly }, modifier = Modifier.padding(horizontal = 12.dp),
                label = { Text("Needs input") })
        }
        val matchingAgents = snapshot.panes.filter { pane ->
            val workspaceLabel = snapshot.workspaces.find { it.id == pane.workspaceId }?.label.orEmpty()
            pane.agent != null && (!waitingOnly || pane.status == "blocked") &&
                listOf(pane.displayName, pane.agent, workspaceLabel).any { it.contains(agentQuery.trim(), ignoreCase = true) }
        }
        val agents = when (agentSort) {
            "name" -> matchingAgents.sortedBy { it.displayName.lowercase() }
            "attention" -> matchingAgents.sortedBy { when (it.status) { "blocked" -> 0; "done" -> 1; "working" -> 2; else -> 3 } }
            else -> matchingAgents.sortedBy { pane -> snapshot.workspaces.indexOfFirst { it.id == pane.workspaceId } }
        }
        items(agents, key = { "agent:${it.id}" }) { agent ->
            ListItem(headlineContent = { Text(agent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${statusLabel(agent.status)} · ${snapshot.workspaces.firstOrNull { it.id == agent.workspaceId }?.label.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall) }, leadingContent = { StatusDot(agent.status) },
                colors = ListItemDefaults.colors(containerColor = if (paneId == agent.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).semantics { selected = paneId == agent.id }.clickable { onAgent(agent.id) })
        }
        if (agents.isEmpty()) item { Text(when {
            agentQuery.isNotBlank() -> "No matching agents"
            waitingOnly -> "No agents need input"
            else -> "No active agents"
        }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun WorkspaceToolbar(server: ServerState, serverName: String, showMenu: Boolean, openMenu: () -> Unit, onRefresh: () -> Unit, onTab: (String) -> Unit, onAction: (WorkspaceAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    val workspace = server.snapshot?.workspaces?.firstOrNull { it.id == server.workspaceId }
    val tabs = server.snapshot?.tabs.orEmpty().filter { it.workspaceId == server.workspaceId }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val tabLabel = tabs.find { it.id == server.tabId }?.label ?: "Tabs"
    val blocked = server.snapshot?.panes?.count { it.status == "blocked" } ?: 0
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenu) IconButton(onClick = openMenu) {
            BadgedBox(badge = { if (blocked > 0) { if (largeText) Badge() else Badge { Text(blocked.toString()) } } }) {
                Icon(Icons.Default.Menu, if (blocked > 0) "Workspaces and agents, $blocked need input" else "Workspaces and agents",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(workspace?.label ?: "Herdr", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (largeText && workspace != null) "$serverName · $tabLabel" else serverName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (workspace != null && largeText) IconButton(onClick = { tabsOpen = true }, modifier = Modifier.semantics { contentDescription = "Tabs"; stateDescription = tabLabel }) {
            Icon(Icons.Default.ArrowDropDown, null)
        }
        else if (workspace != null) TextButton(onClick = { tabsOpen = true }, modifier = Modifier.width(104.dp).semantics { contentDescription = "Tabs"; stateDescription = tabs.find { it.id == server.tabId }?.label ?: "No tab selected" }) {
            Text(tabs.find { it.id == server.tabId }?.label ?: "Tabs", modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Workspace actions") }
    }
    if (tabsOpen) ActionSheet("Tabs", "$serverName · ${workspace?.label.orEmpty()}", listOf(
        ActionSection("In this workspace", tabs.map { tab -> SheetAction(tab.label,
            detail = "${server.snapshot?.panes?.count { it.tabId == tab.id } ?: 0} panes",
            selected = tab.id == server.tabId, onClick = { onTab(tab.id) }) }),
        ActionSection("Create", listOf(SheetAction("New tab", detail = "Start a separate terminal layout") { onAction(WorkspaceAction.CREATE_TAB) })),
    ), onDismiss = { tabsOpen = false })
    if (expanded) {
        val groups = linkedMapOf(
            "Tab" to listOf(WorkspaceAction.CREATE_TAB, WorkspaceAction.RENAME_TAB, WorkspaceAction.REORDER_TAB, WorkspaceAction.CLOSE_TAB),
            "Workspace" to listOf(WorkspaceAction.CREATE_WORKSPACE, WorkspaceAction.RENAME_WORKSPACE, WorkspaceAction.REORDER_WORKSPACE, WorkspaceAction.CLOSE_WORKSPACE),
            "Worktrees" to listOf(WorkspaceAction.CREATE_WORKTREE, WorkspaceAction.WORKTREES, WorkspaceAction.REORDER_GROUP, WorkspaceAction.CLOSE_GROUP),
            "Tools" to listOf(WorkspaceAction.IMPORT_LAYOUT, WorkspaceAction.LAYOUT, WorkspaceAction.COMMANDS, WorkspaceAction.PLUGINS, WorkspaceAction.LINK_PLUGIN, WorkspaceAction.PLUGIN_LOGS, WorkspaceAction.INTEGRATIONS, WorkspaceAction.CLOSE_POPUP),
        )
        ActionSheet("Workspace actions", "$serverName · ${workspace?.label ?: "No workspace"}",
            groups.map { (label, actions) -> ActionSection(label, actions.map { action ->
                SheetAction(action.title, enabled = action.available(server), destructive = action.isDestructive,
                    onClick = { onAction(action) })
            }) } + ActionSection("Connection", listOf(SheetAction("Refresh connection") { onRefresh() })),
            onDismiss = { expanded = false })
    }
}

internal val WorkspaceAction.isDestructive: Boolean get() = this in setOf(
    WorkspaceAction.CLOSE_PANE, WorkspaceAction.CLOSE_TAB, WorkspaceAction.CLOSE_WORKSPACE,
    WorkspaceAction.CLOSE_GROUP, WorkspaceAction.CLOSE_POPUP, WorkspaceAction.CLEAR_PANE,
)

@Composable
internal fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = LocalHerdrStatusColors.current.forStatus(status)
    Box(modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (status == "blocked") Box(Modifier.size(12.dp).border(1.dp, color.copy(alpha = 0.35f), CircleShape))
        val dot = Modifier.size(8.dp)
        Box(when (status) {
            "done" -> dot.border(2.dp, color, CircleShape)
            "working", "blocked", "idle" -> dot.background(color, CircleShape)
            else -> dot.border(1.5.dp, color, CircleShape)
        })
    }
}

private fun WorkspaceAction.available(server: ServerState): Boolean = when (this) {
    WorkspaceAction.CLOSE_GROUP, WorkspaceAction.REORDER_GROUP -> server.snapshot?.workspaces?.find { it.id == server.workspaceId }?.worktree != null
    WorkspaceAction.CREATE_WORKSPACE, WorkspaceAction.LINK_PLUGIN, WorkspaceAction.PLUGINS,
    WorkspaceAction.INTEGRATIONS, WorkspaceAction.PLUGIN_LOGS, WorkspaceAction.COMMANDS, WorkspaceAction.CLOSE_POPUP -> server.connected
    WorkspaceAction.CREATE_TAB, WorkspaceAction.IMPORT_LAYOUT, WorkspaceAction.CREATE_WORKTREE, WorkspaceAction.WORKTREES,
    WorkspaceAction.RENAME_WORKSPACE, WorkspaceAction.CLOSE_WORKSPACE, WorkspaceAction.REORDER_WORKSPACE -> server.workspaceId != null
    WorkspaceAction.RENAME_TAB, WorkspaceAction.CLOSE_TAB, WorkspaceAction.REORDER_TAB, WorkspaceAction.LAYOUT -> server.tabId != null
    else -> server.paneId != null
}
