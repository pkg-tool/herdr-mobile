package dev.herdr.android

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val fingerprint: String,
    val session: String = "default",
    val executable: String = ".local/bin/herdr",
) {
    fun validate() {
        require(name.isNotBlank()) { "Enter a server name" }
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\@" }) {
            "Enter a hostname or IP address without ssh://"
        }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(username.isNotBlank() && username.none { it.isWhitespace() }) { "Enter an SSH username" }
        require(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(fingerprint)) {
            "Enter the SSH host key SHA256 fingerprint"
        }
        require(session.isNotBlank()) { "Enter a Herdr session" }
        require(executable.isNotBlank()) { "Enter the path to Herdr" }
        require(listOf(host, username, session, executable).none { '\u0000' in it || '\n' in it || '\r' in it }) {
            "Connection fields must not contain control characters"
        }
    }

    fun bridgeCommand(): String =
        "exec ${shellQuote(executable)} --session ${shellQuote(session)} remote-api-bridge"
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

@Serializable
data class Snapshot(
    val version: String,
    val workspaces: List<Workspace>,
    val tabs: List<Tab>,
    val panes: List<Pane>,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    @SerialName("boot_id") val bootId: String? = null,
    val commands: List<ServerCommand> = emptyList(),
    val revision: Long = 0,
)

@Serializable
data class ServerCommand(val id: String, val label: String)

@Serializable
data class Workspace(
    @SerialName("workspace_id") val id: String,
    val label: String,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("agent_status") val status: String = "unknown",
    val worktree: Worktree? = null,
)

@Serializable
data class Worktree(
    @SerialName("repo_key") val repoKey: String,
    @SerialName("repo_name") val repoName: String,
)

@Serializable
data class Tab(
    @SerialName("tab_id") val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val label: String,
    val zoomed: Boolean = false,
)

@Serializable
data class Pane(
    @SerialName("pane_id") val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val label: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val cwd: String? = null,
    @SerialName("agent_status") val status: String = "unknown",
) {
    val displayName: String get() = label ?: title ?: agent ?: id
}

@Serializable
data class PaneOutput(
    @SerialName("pane_id") val paneId: String,
    val text: String,
    val truncated: Boolean,
)
