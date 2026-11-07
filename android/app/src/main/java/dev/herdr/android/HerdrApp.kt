package dev.herdr.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HerdrApp(model: HerdrViewModel) {
    val snackbar = remember { SnackbarHostState() }
    ClientEffects(model, snackbar)
    val imeVisible = WindowInsets.isImeVisible
    val app by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    var clientSettings by rememberSaveable { mutableStateOf(false) }
    val profile = app.profiles.firstOrNull { it.id == app.selectedId }
    val server = app.servers[profile?.id] ?: ServerState()
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var authenticating by remember { mutableStateOf<ServerProfile?>(null) }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.focus(true)
                Lifecycle.Event.ON_STOP -> model.focus(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val serverNavigation: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Herdr", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { adding = true }) { Text("+ Server") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.profiles.forEach { item ->
                    FilterChip(profile?.id == item.id, { model.select(item.id) }, label = {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }, leadingIcon = { StatusDot(if (app.servers[item.id]?.connected == true) "idle" else "unknown") })
                }
            }
            if (profile != null) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = profile }) { Text("Server settings") }
                if (server.connected) TextButton(onClick = { model.disconnect(profile.id) }) { Text("Disconnect") }
            }
        }
    }
    val navigationFooter: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { clientSettings = true }) { Text("App settings") }
            Text(server.snapshot?.version.orEmpty(), style = MaterialTheme.typography.labelSmall)
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (!server.connected) { serverNavigation(); navigationFooter() }
            app.storageError?.let { ErrorMessage("Could not save: $it") }
            if (profile == null) {
                EmptyState("Your agents, wherever you are", "Add an SSH server running a Herdr session.") {
                    Button(onClick = { adding = true }) { Text("Add server") }
                }
            } else if (!server.connected) {
                EmptyState(profile.name, "${profile.username}@${profile.host}:${profile.port} · ${profile.session}") {
                    server.error?.let { ErrorMessage(it) }
                    if (server.connecting) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Text("Connecting securely over SSH…", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.disconnect(profile.id) }) { Text("Cancel") }
                    } else {
                        Button(onClick = { authenticating = profile }) { Text(if (server.error == null) "Connect" else "Try again") }
                        if (server.error != null) TextButton(onClick = { editing = profile }) { Text("Edit server") }
                    }
                }
            } else {
                if (server.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                server.error?.let { ErrorMessage(it) }
                ServerContent(profile.id, profile.name, server, model, serverNavigation, navigationFooter, Modifier.weight(1f))
            }
        }
    }
    if (clientSettings) ClientSettingsDialog(settings, model::updateSettings) { clientSettings = false }
    if (adding || editing != null) ProfileDialog(
        profile = editing,
        onDismiss = { adding = false; editing = null },
        onSave = { model.save(it); adding = false; editing = null },
        onRemove = { removing = it; editing = null },
    )
    authenticating?.let { target -> CredentialsDialog(
        target, passwords = model.passwords, passwordRejected = app.servers[target.id]?.passwordRejected == true,
        onDismiss = { authenticating = null },
        onConnect = { password, key, saved -> model.connect(target, password, key, saved); authenticating = null },
    ) }
    removing?.let { target -> AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text("Remove ${target.name}?") },
        text = { Text("This profile will be removed from this device. Herdr will keep running on the server.") },
        confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), onClick = { model.remove(target.id); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
    ) }
}

@Composable
private fun ServerContent(id: String, name: String, server: ServerState, model: HerdrViewModel,
    navigationHeader: @Composable () -> Unit, navigationFooter: @Composable () -> Unit, modifier: Modifier) {
    var action by remember(id) { mutableStateOf<WorkspaceAction?>(null) }
    var actionPane by remember(id) { mutableStateOf<String?>(null) }
    WorkspaceScreen(server, name, navigationHeader, navigationFooter,
        { model.refresh(id, explicit = true) }, { model.selectWorkspace(id, it) }, { model.selectTab(id, it) },
        { model.selectPane(id, it) }, { actionPane = null; action = it }, modifier) { singlePane ->
        TerminalPane(id, server, model, singlePane, { actionPane = null; action = it }, { paneId, chosen -> actionPane = paneId; action = chosen })
    }
    action?.let { chosen -> OperationDialog(chosen, server.copy(paneId = actionPane ?: server.paneId), serverName = name,
        request = { method, params -> model.request(id, method, params) },
        onDismiss = { action = null }, onSuccess = { result -> model.created(id, result); action = null }) }
}

@Composable
internal fun ErrorMessage(message: String) {
    MessageBanner(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun EmptyState(title: String, detail: String, action: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

internal fun statusLabel(status: String): String = when (status) {
    "working" -> "Working"
    "blocked" -> "Needs input"
    "done" -> "Done"
    "idle" -> "Idle"
    else -> "Unknown status"
}
