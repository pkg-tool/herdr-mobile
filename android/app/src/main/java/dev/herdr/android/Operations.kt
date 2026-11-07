package dev.herdr.android

import kotlinx.serialization.json.*

internal data class OperationRequest(val method: String, val params: JsonObject)
internal data class OperationFields(
    val name: String = "", val directory: String = "", val environment: String = "",
    val branch: String = "", val base: String = "", val path: String = "",
    val agentKind: String = "", val arguments: String = "", val destination: String = "new_tab",
    val destinationTab: String = "", val trustRepository: Boolean = false,
    val layoutJson: String = "", val replaceTab: Boolean = false,
    val insertIndex: Int = -1, val targetPane: String = "", val direction: String = "right", val amount: Float = 0.05f,
)

/** Form values become structured API parameters; none are evaluated as shell commands. */
internal fun operationRequest(action: WorkspaceAction, server: ServerState, fields: OperationFields): OperationRequest {
    fun selected(value: String?, label: String) = requireNotNull(value) { "Select $label" }
    fun required(value: String, label: String): String = value.trim().also { require(it.isNotEmpty()) { "Enter $label" } }
    var method = ""
    val params = buildJsonObject {
        fun optional(key: String, value: String) { if (value.isNotBlank()) put(key, value.trim()) }
        fun workspace() = put("workspace_id", selected(server.workspaceId, "workspace"))
        fun tab() = put("tab_id", selected(server.tabId, "a tab"))
        fun pane() = put("pane_id", selected(server.paneId, "a pane"))
        fun creation() {
            optional("cwd", fields.directory)
            put("focus", true)
            val env = parseEnvironment(fields.environment)
            if (env.isNotEmpty()) put("env", env)
        }
        when (action) {
            WorkspaceAction.CREATE_WORKSPACE -> { method = "workspace.create"; creation(); optional("label", fields.name); server.workspaceId?.let { put("source_workspace_id", it) } }
            WorkspaceAction.CREATE_TAB -> { method = "tab.create"; workspace(); creation(); optional("label", fields.name) }
            WorkspaceAction.SPLIT_RIGHT, WorkspaceAction.SPLIT_DOWN -> {
                method = "pane.split"; put("target_pane_id", selected(server.paneId, "a pane")); creation()
                put("focus", false)
                put("direction", if (action == WorkspaceAction.SPLIT_RIGHT) "right" else "down")
            }
            WorkspaceAction.IMPORT_LAYOUT -> {
                method = "layout.apply"; workspace(); put("focus", true)
                if (fields.replaceTab) tab()
                optional("tab_label", fields.name)
                put("root", validatedLayout(fields.layoutJson))
            }
            WorkspaceAction.REORDER_GROUP -> {
                method = "workspace.move_block"
                val all = server.snapshot?.workspaces.orEmpty()
                val repo = requireNotNull(all.find { it.id == server.workspaceId }?.worktree?.repoKey) { "Select a worktree group" }
                val members = all.filter { it.worktree?.repoKey == repo }.map { it.id }
                require(fields.insertIndex in 0..all.size) { "Select a group position" }
                val before = all.getOrNull(fields.insertIndex)?.id
                require(before !in members) { "Select a position outside the group being moved" }
                put("workspace_ids", JsonArray(members.map(::JsonPrimitive)))
                if (before != null) put("before_workspace_id", before)
            }
            WorkspaceAction.REORDER_WORKSPACE, WorkspaceAction.REORDER_TAB -> {
                val count = if (action == WorkspaceAction.REORDER_WORKSPACE) server.snapshot?.workspaces?.size ?: 0
                    else server.snapshot?.tabs?.count { it.workspaceId == server.workspaceId } ?: 0
                require(fields.insertIndex in 0..count) { "Refresh the item list" }
                if (action == WorkspaceAction.REORDER_WORKSPACE) { method = "workspace.move"; workspace() }
                else { method = "tab.move"; tab() }
                put("insert_index", fields.insertIndex)
            }
            WorkspaceAction.SWAP_PANE -> {
                method = "pane.swap"; put("source_pane_id", selected(server.paneId, "a pane"))
                val other = required(fields.targetPane, "a second pane")
                require(other != server.paneId) { "Select a different pane" }
                put("target_pane_id", other)
            }
            WorkspaceAction.RESIZE_PANE -> {
                method = "pane.resize"; pane()
                require(fields.direction in setOf("left", "right", "up", "down"))
                require(fields.amount.isFinite() && fields.amount in 0.01f..0.5f)
                put("direction", fields.direction); put("amount", fields.amount)
            }
            WorkspaceAction.CLEAR_PANE -> { method = "pane.clear"; pane() }
            WorkspaceAction.EDIT_SCROLLBACK -> { method = "pane.edit_scrollback"; pane() }
            WorkspaceAction.CLOSE_POPUP -> { method = "popup.close" }
            WorkspaceAction.LINK_PLUGIN -> { method = "plugin.link"; put("path", required(fields.path, "the plugin path on the server")); put("enabled", true) }
            WorkspaceAction.RENAME_WORKSPACE -> { method = "workspace.rename"; workspace(); put("label", required(fields.name, "a name")) }
            WorkspaceAction.RENAME_TAB -> { method = "tab.rename"; tab(); put("label", required(fields.name, "a name")) }
            WorkspaceAction.RENAME_PANE -> { method = "pane.rename"; pane(); put("label", fields.name.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull) }
            WorkspaceAction.CLOSE_GROUP -> { method = "workspace.close"; workspace(); put("close_group", true) }
            WorkspaceAction.CLOSE_WORKSPACE -> { method = "workspace.close"; workspace() }
            WorkspaceAction.CLOSE_TAB -> { method = "tab.close"; tab() }
            WorkspaceAction.CLOSE_PANE -> { method = "pane.close"; pane() }
            WorkspaceAction.ZOOM -> { method = "pane.zoom"; pane(); put("mode", "toggle") }
            WorkspaceAction.RENAME_AGENT -> { method = "agent.rename"; put("target", selected(server.paneId, "a pane")); put("name", fields.name.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull) }
            WorkspaceAction.START_AGENT -> {
                method = "agent.start"; pane(); put("name", required(fields.name, "an agent name")); put("kind", required(fields.agentKind, "an agent type"))
                put("args", JsonArray(fields.arguments.lines().filter { it.isNotEmpty() }.map(::JsonPrimitive)))
            }
            WorkspaceAction.MOVE_PANE -> {
                method = "pane.move"; pane(); put("focus", false)
                put("destination", buildJsonObject {
                    require(fields.destination in setOf("tab", "new_tab", "new_workspace"))
                    put("type", fields.destination)
                    when (fields.destination) {
                        "tab" -> { put("tab_id", required(fields.destinationTab, "a destination tab")); put("split", "right") }
                        "new_tab" -> server.workspaceId?.let { put("workspace_id", it) }
                    }
                    if (fields.destination != "tab" && fields.name.isNotBlank()) put("label", fields.name.trim())
                })
            }
            WorkspaceAction.CREATE_WORKTREE -> {
                method = "worktree.create"
                if (fields.directory.isNotBlank()) optional("cwd", fields.directory) else workspace()
                optional("branch", fields.branch); optional("base", fields.base); optional("path", fields.path); optional("label", fields.name)
                put("focus", true); put("trust_repository", fields.trustRepository)
            }
            else -> error("Select a list item for this action")
        }
    }
    return OperationRequest(method, params)
}

internal fun parseEnvironment(text: String): JsonObject = buildJsonObject {
    val names = mutableSetOf<String>()
    text.lines().filter { it.isNotBlank() }.forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "Environment variables: one NAME=value per line" }
        val key = line.substring(0, separator).trim()
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) { "Invalid variable name: $key" }
        require(names.add(key)) { "Variable $key is specified twice" }
        put(key, line.substring(separator + 1))
    }
}

internal fun JsonObject.operationResult(): JsonObject = (get("result") as? JsonObject) ?: this
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.records(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun operationContext(server: ServerState) = buildJsonObject {
    server.workspaceId?.let { put("workspace_id", it) }
    server.tabId?.let { put("tab_id", it) }
    server.paneId?.let { put("focused_pane_id", it) }
}

/** Paths follow server layout.rs: false selects first child, true the second. */
internal fun layoutSplits(root: JsonObject): List<JsonObject> {
    val splits = mutableListOf<JsonObject>()
    fun walk(node: JsonObject, path: List<Boolean>) {
        require(path.size <= 64 && splits.size < 4096) { "Layout is too complex" }
        if (node.text("type") != "split") return
        val ratio = node["ratio"]?.jsonPrimitive?.floatOrNull ?: error("Split ratio is missing")
        require(ratio.isFinite())
        splits += buildJsonObject {
            put("direction", node.text("direction")); put("ratio", ratio)
            put("path", JsonArray(path.map(::JsonPrimitive)))
        }
        walk(node.getValue("first").jsonObject, path + false)
        walk(node.getValue("second").jsonObject, path + true)
    }
    walk(root, emptyList())
    return splits
}

/** Accept an exported layout or a root node, and validate before any launch request. */
internal fun validatedLayout(text: String): JsonObject {
    require(text.length <= 1024 * 1024) { "Layout exceeds 1 MiB" }
    var nesting = 0
    var quoted = false
    var escaped = false
    text.forEach { char ->
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { nesting++; require(nesting <= 128) { "Layout nesting is too deep" } }
            '}', ']' -> nesting--
        }
    }
    require(text.toByteArray().size <= 1024 * 1024) { "Layout exceeds 1 MiB" }
    val document = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw IllegalArgumentException("Invalid layout JSON") }
    val description = (document["layout"] as? JsonObject) ?: document
    val root = (description["root"] as? JsonObject) ?: description
    var count = 0
    fun validate(node: JsonObject, depth: Int) {
        require(depth <= 64 && ++count <= 4096) { "Layout is too complex" }
        when (node.text("type")) {
            "split" -> {
                require(node.text("direction") in setOf("right", "down")) { "Split direction must be right or down" }
                val ratio = (node["ratio"] as? JsonPrimitive)?.floatOrNull
                require(ratio != null && ratio.isFinite() && ratio > 0f && ratio < 1f) { "Split ratio must be between 0 and 1" }
                validate(requireNotNull(node["first"] as? JsonObject) { "First split child is missing" }, depth + 1)
                validate(requireNotNull(node["second"] as? JsonObject) { "Second split child is missing" }, depth + 1)
            }
            "pane" -> {
                listOf("label", "cwd", "pane_id").forEach { key ->
                    val value = node[key]
                    require(value == null || value == JsonNull || value is JsonPrimitive && value.isString && '\u0000' !in value.content) { "Invalid field: $key" }
                }
                node["command"]?.takeUnless { it == JsonNull }?.let { value ->
                    val args = value as? JsonArray
                    require(args != null && args.isNotEmpty() && args.all { it is JsonPrimitive && it.isString && '\u0000' !in it.content } && args.first().jsonPrimitive.content.isNotBlank()) { "Command must be a nonempty argument list" }
                }
                node["env"]?.let { value ->
                    val env = value as? JsonObject
                    require(env != null && env.all { (key, value) -> Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key) && value is JsonPrimitive && value.isString && '\u0000' !in value.content }) { "Invalid pane environment" }
                }
            }
            else -> throw IllegalArgumentException("Node type must be pane or split")
        }
    }
    validate(root, 0)
    return root
}
