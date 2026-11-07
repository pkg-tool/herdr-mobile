package dev.herdr.android

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.herdr.android.endpoint.EndpointMessage
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Platform effects are consumed only while the app is visible. */
@Composable
internal fun ClientEffects(model: HerdrViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(model, context, owner, snackbar) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var notice: Job? = null
            fun show(message: String, action: String? = null, onAction: suspend () -> Unit = {}) {
                notice?.cancel()
                notice = launch {
                    if (snackbar.showSnackbar(message, action, withDismissAction = true,
                            duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        try { onAction() }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            snackbar.showSnackbar(error.description(), withDismissAction = true)
                        }
                    }
                }
            }
            model.effects.collect { effect ->
                val state = model.state.value
                val profile = state.profiles.firstOrNull { it.id == effect.serverId } ?: return@collect
                if (state.servers[effect.serverId]?.connected != true) return@collect
                when (val message = effect.message) {
                    is EndpointMessage.Error -> show("${profile.name}: ${message.message}")
                    is EndpointMessage.Clipboard -> {
                        try {
                            require(message.base64.length <= 1_400_000) { "Clipboard content is too large" }
                            val bytes = Base64.decode(message.base64, Base64.DEFAULT)
                            require(bytes.size <= 1024 * 1024) { "Clipboard content is too large" }
                            val text = Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString()
                            val copy = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Herdr · ${profile.name}", text))
                            }
                            if (state.selectedId == effect.serverId) copy()
                            else show("${profile.name}: clipboard text", "Copy") { copy() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            show("${profile.name}: could not copy text — ${error.description()}")
                        }
                    }
                    is EndpointMessage.Notification -> {
                        if (!model.settings.value.notifications) return@collect
                        val text = listOfNotNull(message.title, message.body?.takeIf(String::isNotBlank)).joinToString("\n")
                        val paneId = message.paneId ?: message.tabId?.let { tabId ->
                            state.servers[effect.serverId]?.snapshot?.panes?.firstOrNull { it.tabId == tabId }?.id
                        }
                        show("${profile.name}: $text", "Open") {
                            model.select(effect.serverId)
                            if (paneId != null) model.selectPane(effect.serverId, paneId)
                            else if (message.workspaceId != null) model.selectWorkspace(effect.serverId, message.workspaceId)
                        }
                    }
                    is EndpointMessage.TerminalBell -> if (message.count > 0 && model.settings.value.bell) {
                        show("${profile.name}: terminal bell", "Open") { model.select(effect.serverId) }
                    }
                    else -> Unit
                }
            }
        }
    }
}
