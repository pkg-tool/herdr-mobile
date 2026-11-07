package dev.herdr.android

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationsTest {
    private val server = ServerState(workspaceId = "w1", tabId = "t2", paneId = "p3")

    @Test fun creationPreservesEnvironmentAndTargetsSelectedPane() {
        val request = operationRequest(WorkspaceAction.SPLIT_DOWN, server,
            OperationFields(directory = "/tmp/my project", environment = "TOKEN=a=b\nEMPTY=\nTEXT=\$(ignored)"))
        assertEquals("pane.split", request.method)
        assertEquals("p3", request.params.text("target_pane_id"))
        assertEquals("down", request.params.text("direction"))
        assertEquals("/tmp/my project", request.params.text("cwd"))
        val env = request.params.getValue("env").jsonObject
        assertEquals("a=b", env.text("TOKEN"))
        assertEquals("", env.text("EMPTY"))
        assertEquals("\$(ignored)", env.text("TEXT"))
    }

    @Test fun moveUsesTaggedDestinationWithoutCreatingAnotherShell() {
        val request = operationRequest(WorkspaceAction.MOVE_PANE, server,
            OperationFields(destination = "tab", destinationTab = "t7"))
        assertEquals("pane.move", request.method)
        assertEquals(buildJsonObject { put("type", "tab"); put("tab_id", "t7"); put("split", "right") }, request.params["destination"])
    }

    @Test fun worktreeExplicitDirectoryDoesNotSendConflictingWorkspace() {
        val params = operationRequest(WorkspaceAction.CREATE_WORKTREE, server,
            OperationFields(directory = "/repo", branch = "feature", trustRepository = false)).params
        assertFalse(params.containsKey("workspace_id"))
        assertEquals("feature", params.text("branch"))
        assertEquals(JsonPrimitive(false), params["trust_repository"])
    }

    @Test fun argumentsAreLiteralAndAgentRenameCanClearName() {
        val params = operationRequest(WorkspaceAction.START_AGENT, server,
            OperationFields(name = "helper", agentKind = "claude", arguments = "--model\nsonnet\nargument with spaces")).params
        assertEquals(listOf("--model", "sonnet", "argument with spaces"), params.getValue("args").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, operationRequest(WorkspaceAction.RENAME_AGENT, server, OperationFields()).params["name"])
    }

    @Test fun reorderUsesOriginalInsertionIndexAndNeverAnotherWorkspaceTabs() {
        val state = server.copy(snapshot = Snapshot("1", listOf(Workspace("w1", "one"), Workspace("w2", "two")),
            listOf(Tab("t1", "w1", "first"), Tab("t2", "w1", "second"), Tab("t3", "w2", "other")), emptyList()))
        val request = operationRequest(WorkspaceAction.REORDER_TAB, state, OperationFields(insertIndex = 2))
        assertEquals("tab.move", request.method)
        assertEquals(JsonPrimitive(2), request.params["insert_index"])
        assertEquals("t2", request.params.text("tab_id"))
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.REORDER_TAB, state, OperationFields(insertIndex = 3)) }
    }

    @Test fun layoutPathsMatchServerFirstSecondConvention() {
        val root = Json.parseToJsonElement("""{"type":"split","direction":"right","ratio":0.5,"first":{"type":"pane"},"second":{"type":"split","direction":"down","ratio":0.6,"first":{"type":"pane"},"second":{"type":"pane"}}}""").jsonObject
        val splits = layoutSplits(root)
        assertEquals(2, splits.size)
        assertEquals(JsonArray(emptyList()), splits[0]["path"])
        assertEquals(JsonArray(listOf(JsonPrimitive(true))), splits[1]["path"])
    }

    @Test fun swapAndResizeSendExplicitTargetsAndRejectInvalidValues() {
        val swap = operationRequest(WorkspaceAction.SWAP_PANE, server, OperationFields(targetPane = "p4"))
        assertEquals("p3", swap.params.text("source_pane_id"))
        assertEquals("p4", swap.params.text("target_pane_id"))
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.SWAP_PANE, server, OperationFields(targetPane = "p3")) }
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.RESIZE_PANE, server, OperationFields(amount = Float.NaN)) }
        val resize = operationRequest(WorkspaceAction.RESIZE_PANE, server, OperationFields(direction = "down", amount = 0.1f))
        assertEquals("pane.resize", resize.method)
        assertEquals("down", resize.params.text("direction"))
    }

    @Test fun layoutImportCreatesNewTabUnlessReplacementWasExplicit() {
        val fields = OperationFields(layoutJson = """{"root":{"type":"pane","command":["printf","hello world"],"env":{"X":"a=b"}}}""")
        val create = operationRequest(WorkspaceAction.IMPORT_LAYOUT, server, fields)
        assertEquals("layout.apply", create.method)
        assertFalse(create.params.containsKey("tab_id"))
        val replace = operationRequest(WorkspaceAction.IMPORT_LAYOUT, server, fields.copy(replaceTab = true))
        assertEquals("t2", replace.params.text("tab_id"))
        assertThrows(IllegalArgumentException::class.java) { validatedLayout("""{"type":"pane","command":"echo unsafe"}""") }
        assertThrows(IllegalArgumentException::class.java) { validatedLayout("[".repeat(129) + "]".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { validatedLayout("""{"type":"split","direction":"right","ratio":2}""") }
    }

    @Test fun groupReorderUsesStableBlockAndExternalBeforeId() {
        val state = server.copy(snapshot = Snapshot("1", listOf(
            Workspace("w1", "root", worktree = Worktree("repo", "repo")),
            Workspace("w2", "child", worktree = Worktree("repo", "repo")),
            Workspace("w3", "other")), emptyList(), emptyList()))
        val request = operationRequest(WorkspaceAction.REORDER_GROUP, state, OperationFields(insertIndex = 2))
        assertEquals("workspace.move_block", request.method)
        assertEquals(JsonArray(listOf(JsonPrimitive("w1"), JsonPrimitive("w2"))), request.params["workspace_ids"])
        assertEquals("w3", request.params.text("before_workspace_id"))
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.REORDER_GROUP, state, OperationFields(insertIndex = 1)) }
    }

    @Test fun invalidFormsCannotSendMutation() {
        assertThrows(IllegalArgumentException::class.java) { parseEnvironment("A=1\nA=2") }
        assertThrows(IllegalArgumentException::class.java) { parseEnvironment("not a key=x") }
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.CLOSE_PANE, ServerState(), OperationFields()) }
        assertThrows(IllegalArgumentException::class.java) { operationRequest(WorkspaceAction.START_AGENT, server, OperationFields()) }
    }
}
