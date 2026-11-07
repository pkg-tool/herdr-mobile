package dev.herdr.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileDialog(
    profile: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit,
    onRemove: (ServerProfile) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(profile?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(profile?.port?.toString() ?: "22") }
    var username by rememberSaveable { mutableStateOf(profile?.username.orEmpty()) }
    var fingerprint by rememberSaveable { mutableStateOf(profile?.fingerprint.orEmpty()) }
    var session by rememberSaveable { mutableStateOf(profile?.session ?: "default") }
    var executable by rememberSaveable { mutableStateOf(profile?.executable ?: ".local/bin/herdr") }
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var fingerprintHelp by rememberSaveable { mutableStateOf(false) }
    var commandCopied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formScroll = rememberScrollState()
    val formScope = rememberCoroutineScope()
    val connectionKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false, imeAction = ImeAction.Next)
    val fingerprintCommand = "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"
    FormDialog(
        scrollState = formScroll,
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Add server" else "Server settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { MessageBanner(it) }
                SectionLabel("Connection")
                OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(host, { host = it; error = null }, Modifier.fillMaxWidth(), label = { Text("SSH server address") },
                    supportingText = { Text("Hostname or IP address, without ssh://") }, singleLine = true,
                    keyboardOptions = connectionKeyboard.copy(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it; error = null }, Modifier.fillMaxWidth(), label = { Text("SSH port") }, singleLine = true,
                    keyboardOptions = connectionKeyboard.copy(keyboardType = KeyboardType.Number))
                OutlinedTextField(username, { username = it; error = null }, Modifier.fillMaxWidth(), label = { Text("SSH user") }, singleLine = true,
                    keyboardOptions = connectionKeyboard)
                HorizontalDivider()
                SectionLabel("Server identity")
                OutlinedTextField(fingerprint, { fingerprint = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Server key fingerprint") },
                    supportingText = { Text("SHA256 fingerprint from a trusted source.") },
                    keyboardOptions = connectionKeyboard.copy(imeAction = ImeAction.Done))
                TextButton(onClick = { fingerprintHelp = !fingerprintHelp }) {
                    Text(if (fingerprintHelp) "Hide fingerprint instructions" else "How to find the fingerprint")
                }
                if (fingerprintHelp) {
                    Text("Run this on the server, then copy the SHA256 value here. Verify it over a connection you trust.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(fingerprintCommand, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("SSH fingerprint command", fingerprintCommand))
                        commandCopied = true
                    }) { Text(if (commandCopied) "Command copied" else "Copy command") }
                }
                HorizontalDivider()
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "Hide advanced settings" else "Advanced settings")
                }
                if (advanced) {
                    OutlinedTextField(session, { session = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Herdr session") }, singleLine = true,
                        keyboardOptions = connectionKeyboard)
                    OutlinedTextField(executable, { executable = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Herdr executable path") }, singleLine = true,
                        supportingText = { Text("Absolute path or relative to the SSH user’s home directory.") },
                        keyboardOptions = connectionKeyboard.copy(imeAction = ImeAction.Done))
                }
                Text("You’ll choose a password or private key when connecting.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile != null) {
                    HorizontalDivider()
                    TextButton(onClick = { onRemove(profile) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove server") }
                }
            }
        },
        confirmButton = { Button(onClick = {
            try {
                val updated = ServerProfile(
                    id = profile?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(), host = host.trim().removeSurrounding("[", "]"),
                    port = port.toIntOrNull() ?: 0, username = username.trim(), fingerprint = Regex("SHA256:[A-Za-z0-9+/]{43}").find(fingerprint)?.value ?: fingerprint.trim(),
                    session = session.trim(), executable = executable.trim(),
                )
                updated.validate()
                onSave(updated)
            } catch (failure: IllegalArgumentException) {
                error = failure.message
                if (session.isBlank() || executable.isBlank()) advanced = true
                formScope.launch { formScroll.animateScrollTo(0) }
            }
        }) { Text(if (profile == null) "Add server" else "Save changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun CredentialsDialog(
    profile: ServerProfile,
    passwords: PasswordStore,
    passwordRejected: Boolean = false,
    onDismiss: () -> Unit,
    onConnect: (String, ByteArray?, PendingSavedPassword?) -> Unit,
) {
    // Credentials deliberately do not use rememberSaveable (saved state is persisted by Android).
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<ByteArray?>(null) }
    var reading by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pastedKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val unlock = (LocalActivity.current as MainActivity).biometricUnlock
    var saved by remember(profile.id) { mutableStateOf(false) }
    var checkingSaved by remember(profile.id) { mutableStateOf(true) }
    var useSaved by remember(profile.id) { mutableStateOf(saved) }
    var savePassword by remember { mutableStateOf(false) }
    var authenticating by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(profile.id) {
        saved = withContext(Dispatchers.IO) { passwords.contains(profile.id) }
        useSaved = saved && !passwordRejected
        if (passwordRejected) error = "SSH rejected the password. Enter it again or use a key."
        checkingSaved = false
    }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            error = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openInputStream(uri)) { "Could not open the key" }.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= 65_536) { "Key file exceeds 64 KiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().also { require(it.isNotEmpty()) { "Key file is empty" } }
                    }
                }
                key?.fill(0)
                key = bytes
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not read the key"
            } finally { reading = false }
        }
    }
    DisposableEffect(Unit) { onDispose { key?.fill(0); password = ""; pastedKey = "" } }
    FormDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false),
        onDismissRequest = { operation?.cancel(); onDismiss() },
        title = { Text("Connect to ${profile.name}") },
        text = {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${profile.username}@${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (checkingSaved) { Text("Checking saved password…") } else if (useSaved) {
                    Text("Your saved password is protected with biometrics. Unlock it to reconnect.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { useSaved = false; error = null }, enabled = !authenticating) { Text("Use password or private key") }
                } else {
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (key == null) "SSH password" else "Private key passphrase (if any)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !authenticating)
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !reading && !authenticating) {
                        Text(if (reading) "Reading key…" else if (key == null) "Use private key" else "Choose another key")
                    }
                    TextButton(onClick = { pasting = !pasting }, enabled = !authenticating) { Text("Paste private key") }
                    if (pasting) OutlinedTextField(pastedKey, { value ->
                        pastedKey = value.take(65_536)
                        key?.fill(0)
                        key = pastedKey.trim().takeIf { it.isNotEmpty() }?.let { (it + "\n").toByteArray() }
                    }, Modifier.fillMaxWidth().heightIn(max = 140.dp), label = { Text("PEM / OpenSSH private key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !authenticating)
                    if (key != null) TextButton(onClick = { key?.fill(0); key = null; pastedKey = ""; pasting = false }, enabled = !authenticating) { Text("Use password instead") }
                    if (key == null) {
                        ConfirmationRow(text = "Save password for biometric reconnect", checked = savePassword,
                            onCheckedChange = { savePassword = it }, enabled = !authenticating && unlock.available())
                        if (!unlock.available()) Text("Set up strong biometrics in Android settings to save a password.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (savePassword && key == null) "Saved only after successful SSH authentication. Biometric confirmation is required to save and reconnect."
                        else "Credentials are used only for this connection.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saved) TextButton(onClick = { useSaved = true; error = null }, enabled = !authenticating) { Text("Use saved password") }
                }
                if (saved) TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { passwords.forget(profile.id) }
                            saved = false; useSaved = false; error = null
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = "Could not remove the saved password."
                        }
                    }
                }, enabled = !authenticating) { Text("Forget saved password") }
                error?.let { MessageBanner(it) }
            }
        },
        confirmButton = { Button(onClick = {
            operation = scope.launch {
                authenticating = true
                error = null
                try {
                    if (useSaved) {
                        val encrypted = withContext(Dispatchers.IO) { passwords.read(profile.id) }
                        val cipher = withContext(Dispatchers.IO) { passwords.decryptionCipher(profile, encrypted) }
                        val authorized = unlock.authenticate(cipher, "Reconnect to ${profile.name}", "${profile.username}@${profile.host}:${profile.port}")
                        val unlocked = withContext(Dispatchers.IO) { passwords.decrypt(profile, authorized, encrypted) }
                        onConnect(unlocked, null, null)
                    } else {
                        val encrypted = if (savePassword && key == null) {
                            val prepared = withContext(Dispatchers.IO) { passwords.prepareSave(profile) }
                            val authorized = unlock.authenticate(prepared.cipher, "Save SSH password", profile.name)
                            PendingSavedPassword(withContext(Dispatchers.IO) { passwords.encrypt(profile, authorized, password) }, prepared.generation)
                        } else null
                        onConnect(password, key?.copyOf(), encrypted)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val unusable = failure is android.security.keystore.KeyPermanentlyInvalidatedException ||
                        failure is java.security.UnrecoverableKeyException || failure is SavedPasswordUnavailable ||
                        failure is javax.crypto.AEADBadTagException
                    if (unusable) {
                        withContext(Dispatchers.IO) { passwords.forget(profile.id) }
                        saved = false; useSaved = false
                    }
                    error = if (unusable) "Saved password could not be used and was removed. Enter your password again."
                    else when (failure) {
                        is java.security.GeneralSecurityException, is java.io.IOException, is IllegalArgumentException ->
                            "Could not unlock this password. Forget it and save it again, or connect manually."
                        else -> failure.message ?: "Biometric authentication unavailable. Connect manually."
                    }
                } finally { authenticating = false }
            }
        }, enabled = !checkingSaved && !reading && !authenticating && (useSaved || password.isNotEmpty() || key != null)) {
            Text(if (authenticating) "Authenticating…" else if (useSaved) "Connect with biometrics" else "Connect")
        } },
        dismissButton = { TextButton(onClick = { operation?.cancel(); onDismiss() }) { Text("Cancel") } },
    )
}
