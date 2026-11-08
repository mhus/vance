package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.execution.ExecutionOwner;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class McpProjectServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "showcase";
    private static final String USER = "_agent";
    private static final McpCaller ACCOUNT = McpCaller.account(USER);
    private static final McpCaller TOKEN = McpCaller.integration(USER, "tok-1");

    @Mock
    McpProjectAccessService accessService;

    PermissionService permissionService = mock(PermissionService.class);

    SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);

    ExecutionRegistryService executionRegistry = new ExecutionRegistryService();

    McpProjectService service;
    Map<String, Tool> tools;

    @BeforeEach
    void setUp() {
        tools = stubTools();
        McpProjectToolCatalog catalog = new McpProjectToolCatalog(new ArrayList<>(tools.values()));
        // An account that may execute, unless a test says otherwise.
        lenient()
                .when(permissionService.check(any(), any(), eq(Action.EXECUTE)))
                .thenReturn(true);
        service = new McpProjectService(
                accessService, catalog, new ObjectMapper(), permissionService, contextFactory, executionRegistry);
    }

    // ──────────────────── tools/list ────────────────────

    @Test
    void toolsList_withoutMatchingEntry_isEmptyCatalogue() {
        when(accessService.resolveEntry(TENANT, PROJECT, ACCOUNT)).thenReturn(null);

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, ACCOUNT);

        assertThat(toolNamesOf(out)).isEmpty();
    }

    @Test
    void toolsList_roEntry_listsOnlyReadTools() {
        entry(Mode.RO, List.of());

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, TOKEN);

        assertThat(toolNamesOf(out))
                .containsExactly(
                        "doc_read", "doc_read_lines", "doc_list_in_folder", "doc_list_folders", "doc_grep_path");
    }

    @Test
    void toolsList_rwEntry_listsReadAndWriteTools() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, TOKEN);

        assertThat(toolNamesOf(out))
                .hasSize(11)
                .contains("doc_read", "doc_write", "doc_move", "doc_copy", "doc_delete")
                .noneMatch(n -> n.startsWith("workflow_"));
    }

    // ──────────────────── tools/call: access ────────────────────

    @Test
    void toolsCall_withoutMatchingEntry_isError() {
        when(accessService.resolveEntry(TENANT, PROJECT, TOKEN)).thenReturn(null);

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("mcp-access.yaml");
    }

    @Test
    void toolsCall_unknownTool_isError() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out =
                service.handle(callBody("doc_purge", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("Unknown tool");
    }

    @Test
    void toolsCall_writeToolUnderRoMode_isError() {
        entry(Mode.RO, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "spec/x.md", "content", "hi")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'ro'");
        verify(tools.get("doc_write"), never()).invoke(anyMap(), any());
    }

    // ──────────────────── tools/call: surface confinement ────────────────────

    @Test
    void toolsCall_idAddressing_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(callBody("doc_read", Map.of("id", "6626abc")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'path' only");
    }

    @Test
    void toolsCall_foreignProjectId_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_read", Map.of("projectId", "other", "path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("pinned to project '" + PROJECT + "'");
    }

    @Test
    void toolsCall_pathOutsidePrefixes_isRejected() {
        entry(Mode.RW, List.of("spec/"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "readme/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("outside the prefixes");
        verify(tools.get("doc_read"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_missingPathScopeUnderPrefixes_isRejected() {
        entry(Mode.RO, List.of("spec/"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_grep_path", Map.of("pattern", "foo")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("path-scoped");
    }

    @Test
    void toolsCall_wildcardScopeUnderPrefixes_isRejected() {
        entry(Mode.RO, List.of("spec/"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_list_in_folder", Map.of("pathPrefix", "*")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("whole-project scope");
    }

    @Test
    void toolsCall_writeIntoVanceArea_isRejectedEvenForWholeProjectRw() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "_vance/config/mcp-access.yaml", "content", "own the world")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_write"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_aliasedPathParams_everyOneIsChecked() {
        // The tool prefers pathPrefix over folder — an allowed folder must
        // not smuggle a forbidden pathPrefix past the check.
        entry(Mode.RO, List.of("docs/"));

        McpProtocol.Outcome out = service.handle(
                callBody("doc_list_in_folder", Map.of("folder", "docs/", "pathPrefix", "secret/")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("outside the prefixes");
        verify(tools.get("doc_list_in_folder"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_writeIntoVanceAreaViaRepeatedSlashes_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "//_vance/config/mcp-access.yaml", "content", "own the world")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_write"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_writeToBareVanceFolder_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "/_vance/", "content", "x")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
    }

    // ──────────────────── tools/call: move / copy / delete ────────────────────

    @Test
    void toolsCall_moveTargetOutsidePrefixes_isRejected() {
        entry(Mode.RW, List.of("spec/"));

        McpProtocol.Outcome out = service.handle(
                callBody("doc_move", Map.of("path", "spec/x.md", "newPath", "readme/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("outside the prefixes");
        verify(tools.get("doc_move"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_copyIntoVanceArea_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_copy", Map.of("path", "spec/x.md", "newPath", "_vance/trash/x.md")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_copy"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_moveOutOfTrash_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_move", Map.of("path", "_vance/trash/1234_x.md", "newPath", "spec/x.md")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_move"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_deleteInsideTrash_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_delete", Map.of("path", "_vance/trash/1234_x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_delete"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_deleteUnderRoMode_isError() {
        entry(Mode.RO, List.of());

        McpProtocol.Outcome out =
                service.handle(callBody("doc_delete", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'ro'");
        verify(tools.get("doc_delete"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_moveWithinPrefixes_invokesTool() {
        entry(Mode.RW, List.of("spec/"));
        Map<String, Object> args = Map.of("path", "spec/old.md", "newPath", "spec/new.md");
        when(tools.get("doc_move").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("previousPath", "spec/old.md", "newPath", "spec/new.md"));

        McpProtocol.Outcome out = service.handle(callBody("doc_move", args), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
        verify(tools.get("doc_move")).invoke(eq(args), any(ToolInvocationContext.class));
    }

    // ──────────────────── tools/call: happy path & failures ────────────────────

    @Test
    void toolsCall_allowedPath_invokesToolAndReturnsResult() {
        entry(Mode.RW, List.of("spec/"));
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("path", "spec/design.md", "content", "hello"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/design.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
        assertThat(textOf(out)).contains("spec/design.md");
        verify(tools.get("doc_read")).invoke(eq(Map.of("path", "spec/design.md")), any(ToolInvocationContext.class));
    }

    @Test
    void toolsCall_toolException_isErrorWithHint() {
        entry(Mode.RW, List.of());
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenThrow(new ToolException("not found"));
        when(tools.get("doc_read").troubleshootingHint()).thenReturn("check the path");

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("not found").contains("check the path");
    }

    @Test
    void toolsCall_runtimeException_isWrappedAsToolFailure() {
        entry(Mode.RW, List.of());
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenThrow(new IllegalStateException("boom"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("failed");
    }

    // ──────────────────── capabilities ────────────────────

    @Test
    void toolsList_entryWithoutCapabilities_isEmptyCatalogue() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, TOKEN);

        assertThat(toolNamesOf(out)).isNotEmpty(); // sanity: the helper's default grants doc
        entryWithNoCapabilities();
        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .isEmpty();
    }

    @Test
    void toolsCall_entryWithoutCapabilities_isDeniedAndSaysHowToFixIt() {
        entryWithNoCapabilities();

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("grants no capabilities").contains("capabilities:");
        verify(tools.get("doc_read"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void toolsList_workflowCapability_roSeesTheInspectionToolsOnly() {
        entry(Mode.RO, List.of(), McpProjectCapability.WORKFLOW);

        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .containsExactly("workflow_list", "workflow_get", "workflow_runs", "workflow_run_get");
    }

    @Test
    void toolsList_workflowCapability_rwAddsTheSteeringTools() {
        entry(Mode.RW, List.of(), McpProjectCapability.WORKFLOW);

        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .containsExactly(
                        "workflow_list",
                        "workflow_get",
                        "workflow_runs",
                        "workflow_run_get",
                        "workflow_start",
                        "workflow_control",
                        "workflow_breakpoints",
                        "workflow_simulate",
                        "workflow_restart");
    }

    @Test
    void toolsList_bothCapabilities_listsBothFamiliesInDefinitionOrder() {
        entry(Mode.RO, List.of(), McpProjectCapability.WORKFLOW, McpProjectCapability.DOC);

        List<String> names = toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN));

        assertThat(names).hasSize(9).startsWith("doc_read").endsWith("workflow_run_get");
    }

    @Test
    void toolsCall_toolOfAFamilyTheEntryDoesNotGrant_isDenied() {
        entry(Mode.RW, List.of(), McpProjectCapability.DOC);

        McpProtocol.Outcome out = service.handle(callBody("workflow_runs", Map.of()), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("needs the capability 'workflow'");
        verify(tools.get("workflow_runs"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void toolsCall_workflowWriteToolInRoEntry_isDenied() {
        entry(Mode.RO, List.of(), McpProjectCapability.WORKFLOW);

        McpProtocol.Outcome out = service.handle(
                callBody("workflow_control", Map.of("runId", "r1", "action", "stop")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("not available in access mode 'ro'");
    }

    @Test
    void toolsCall_workflowRun_reachesTheToolWithTheCallersIdentity() {
        entry(Mode.RW, List.of(), McpProjectCapability.WORKFLOW);
        when(tools.get("workflow_run_get").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("status", "PAUSED"));

        McpProtocol.Outcome out =
                service.handle(callBody("workflow_run_get", Map.of("runId", "r1")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
        ArgumentCaptor<ToolInvocationContext> ctx = ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(tools.get("workflow_run_get")).invoke(anyMap(), ctx.capture());
        assertThat(ctx.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(ctx.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(ctx.getValue().userId()).isEqualTo(USER);
    }

    @Test
    void toolsCall_workflowStartByPath_isRefused_byNameIsFine() {
        entry(Mode.RW, List.of(), McpProjectCapability.WORKFLOW);
        when(tools.get("workflow_start").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("workflowRunId", "r1"));

        McpProtocol.Outcome byPath =
                service.handle(callBody("workflow_start", Map.of("path", "docs/plan.yaml")), TENANT, PROJECT, TOKEN);
        McpProtocol.Outcome byName =
                service.handle(callBody("workflow_start", Map.of("name", "release")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(byPath)).isTrue();
        assertThat(textOf(byPath)).contains("'path' is not accepted").contains("by 'name'");
        assertThat(isErrorOf(byName)).isFalse();
        verify(tools.get("workflow_start"), org.mockito.Mockito.times(1)).invoke(anyMap(), any());
    }

    @Test
    void catalog_withoutTheWorkflowTools_offersNoWorkflowCapability() {
        // Magrathea is a feature switch: on a Brain without it the family is not on the classpath and is
        // simply not offered — the boot must not fail over it.
        List<Tool> docOnly = tools.values().stream()
                .filter(t -> !t.name().startsWith("workflow_"))
                .toList();
        McpProjectToolCatalog catalog = new McpProjectToolCatalog(new ArrayList<>(docOnly));
        McpProjectAccessConfig.Entry both = new McpProjectAccessConfig.Entry(
                "e",
                McpCallerFilter.ANY,
                Mode.RW,
                List.of(McpProjectCapability.DOC, McpProjectCapability.WORKFLOW),
                List.of());

        assertThat(catalog.visibleTo(both)).hasSize(11).allMatch(e -> e.capability() == McpProjectCapability.DOC);
    }

    @Test
    void catalog_withAHalfPresentWorkflowFamily_failsTheBoot() {
        // A renamed tool must stop the server, not quietly shrink the external contract.
        List<Tool> missingOne = tools.values().stream()
                .filter(t -> !t.name().equals("workflow_restart"))
                .toList();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new McpProjectToolCatalog(new ArrayList<>(missingOne)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow_restart");
    }

    private void entryWithNoCapabilities() {
        McpProjectAccessConfig.Entry entry = new McpProjectAccessConfig.Entry(
                "bare", new McpCallerFilter("tok-1", null, List.of()), Mode.RW, List.of(), List.of());
        when(accessService.resolveEntry(TENANT, PROJECT, TOKEN)).thenReturn(entry);
    }

    // ──────────────────── file and exec ────────────────────

    @Test
    void toolsList_fileCapability_roSeesTheReadTools_rwAddsWriteAndEdit() {
        entry(Mode.RO, List.of(), List.of("build"), McpProjectCapability.FILE);
        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .containsExactly(
                        "work_file_read",
                        "work_file_list",
                        "work_file_find",
                        "work_file_grep",
                        "work_file_head_tail",
                        "work_file_count");

        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.FILE);
        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .hasSize(8)
                .endsWith("work_file_write", "work_file_edit")
                .doesNotContain("work_file_delete");
    }

    @Test
    void toolsList_execCapability_listsTheCommandTools() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.EXEC);

        assertThat(toolNamesOf(service.handle(toolsListBody(), TENANT, PROJECT, TOKEN)))
                .containsExactly(
                        "work_exec_run",
                        "work_exec_status",
                        "work_exec_tail",
                        "work_exec_stat",
                        "work_exec_check",
                        "work_exec_kill");
    }

    @Test
    void fileCall_inAListedDirectory_reachesTheTool() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.FILE);
        when(tools.get("work_file_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("content", "hi"));

        McpProtocol.Outcome out = service.handle(
                callBody("work_file_read", Map.of("dirName", "build", "path", "a.txt")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
        verify(tools.get("work_file_read")).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void fileCall_withoutADirectory_isRefused_thereIsNoDefault() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.FILE);

        McpProtocol.Outcome out =
                service.handle(callBody("work_file_read", Map.of("path", "a.txt")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'dirName' is required").contains("[build]");
        verify(tools.get("work_file_read"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void fileCall_inADirectoryTheEntryDoesNotList_isRefused() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.FILE);

        McpProtocol.Outcome out = service.handle(
                callBody("work_file_write", Map.of("dirName", "secrets", "path", "a", "content", "x")),
                TENANT,
                PROJECT,
                TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("directory 'secrets' is not one of the directories");
        verify(tools.get("work_file_write"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void fileAndExecCalls_needProjectExecute() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.FILE);
        when(permissionService.check(any(), any(), eq(Action.EXECUTE))).thenReturn(false);

        McpProtocol.Outcome out = service.handle(
                callBody("work_file_read", Map.of("dirName", "build", "path", "a.txt")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("EXECUTE is required");
        verify(tools.get("work_file_read"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void documentAndWorkflowCalls_areNotAskedForExecute() {
        // They enforce the finer READ/WRITE themselves; a READER keeps reading documents.
        entry(Mode.RO, List.of(), McpProjectCapability.DOC);
        when(permissionService.check(any(), any(), eq(Action.EXECUTE))).thenReturn(false);
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("content", "x"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
    }

    @Test
    void execRun_inAListedDirectory_reachesTheTool() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.EXEC);
        when(tools.get("work_exec_run").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("status", "COMPLETED"));

        McpProtocol.Outcome out = service.handle(
                callBody("work_exec_run", Map.of("dirName", "build", "command", "make")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
    }

    @Test
    void execRun_withoutADirectory_isRefused_notRunInADefault() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.EXEC);

        McpProtocol.Outcome out =
                service.handle(callBody("work_exec_run", Map.of("command", "make")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
        verify(tools.get("work_exec_run"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void execJobTools_followOnlyJobsOfTheEntrysDirectories() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.EXEC);
        jobInDir("job-ours", "build");
        jobInDir("job-theirs", "other");
        when(tools.get("work_exec_status").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("status", "RUNNING"));

        McpProtocol.Outcome ours =
                service.handle(callBody("work_exec_status", Map.of("id", "job-ours")), TENANT, PROJECT, TOKEN);
        McpProtocol.Outcome theirs =
                service.handle(callBody("work_exec_status", Map.of("id", "job-theirs")), TENANT, PROJECT, TOKEN);
        McpProtocol.Outcome unknown =
                service.handle(callBody("work_exec_kill", Map.of("id", "nope")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(ours)).isFalse();
        assertThat(isErrorOf(theirs)).isTrue();
        // A job of another directory reads exactly like one that does not exist — no confirmation.
        assertThat(textOf(theirs)).contains("Unknown exec job: 'job-theirs'");
        assertThat(isErrorOf(unknown)).isTrue();
        assertThat(textOf(unknown)).contains("Unknown exec job: 'nope'");
        verify(tools.get("work_exec_kill"), never()).invoke(anyMap(), any(ToolInvocationContext.class));
    }

    @Test
    void execJobTools_ignoreAJobOfAnotherProject() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.EXEC);
        executionRegistry.register(new ExecutionRegistryEntry(
                "job-else",
                ExecutionOwner.Brain.INSTANCE,
                TENANT,
                "other-project",
                null,
                null,
                "echo",
                "build",
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                ExecutionStatus.RUNNING,
                null,
                null,
                null,
                Map.of()));

        McpProtocol.Outcome out =
                service.handle(callBody("work_exec_status", Map.of("id", "job-else")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isTrue();
    }

    @Test
    void pathPrefixesOfTheDocumentFamily_doNotBlockTheFileTools() {
        // 'paths' confine documents. An entry that has them and also grants file must not demand a
        // document path on a workspace call.
        entry(Mode.RW, List.of("spec/"), List.of("build"), McpProjectCapability.DOC, McpProjectCapability.FILE);
        when(tools.get("work_file_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("content", "hi"));

        McpProtocol.Outcome out = service.handle(
                callBody("work_file_read", Map.of("dirName", "build", "path", "a.txt")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(out)).isFalse();
    }

    @Test
    void anIdArgument_isOnlyRefusedOnDocumentTools() {
        entry(Mode.RW, List.of(), List.of("build"), McpProjectCapability.DOC, McpProjectCapability.EXEC);
        jobInDir("job-ours", "build");
        when(tools.get("work_exec_status").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("status", "RUNNING"));

        McpProtocol.Outcome doc = service.handle(callBody("doc_read", Map.of("id", "abc")), TENANT, PROJECT, TOKEN);
        McpProtocol.Outcome job =
                service.handle(callBody("work_exec_status", Map.of("id", "job-ours")), TENANT, PROJECT, TOKEN);

        assertThat(isErrorOf(doc)).isTrue();
        assertThat(textOf(doc)).contains("addressed by 'path' only");
        assertThat(isErrorOf(job)).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    private void entry(McpProjectAccessConfig.Mode mode, List<String> paths) {
        entry(mode, paths, McpProjectCapability.DOC);
    }

    private void entry(McpProjectAccessConfig.Mode mode, List<String> paths, McpProjectCapability... capabilities) {
        entry(mode, paths, List.of(), capabilities);
    }

    private void entry(
            McpProjectAccessConfig.Mode mode,
            List<String> paths,
            List<String> dirs,
            McpProjectCapability... capabilities) {
        McpProjectAccessConfig.Entry entry = new McpProjectAccessConfig.Entry(
                "test", new McpCallerFilter("tok-1", null, List.of()), mode, List.of(capabilities), paths, dirs);
        when(accessService.resolveEntry(TENANT, PROJECT, TOKEN)).thenReturn(entry);
    }

    private void jobInDir(String jobId, String dir) {
        executionRegistry.register(new ExecutionRegistryEntry(
                jobId,
                ExecutionOwner.Brain.INSTANCE,
                TENANT,
                PROJECT,
                null,
                null,
                "echo hi",
                dir,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                ExecutionStatus.RUNNING,
                null,
                null,
                null,
                Map.of()));
    }

    /** Short aliases for the real enum constants, to keep the fixtures readable. */
    private interface Mode {
        McpProjectAccessConfig.Mode RO = McpProjectAccessConfig.Mode.RO;
        McpProjectAccessConfig.Mode RW = McpProjectAccessConfig.Mode.RW;
    }

    private static Map<String, Tool> stubTools() {
        Map<String, Tool> out = new LinkedHashMap<>();
        for (String name : List.of(
                "doc_read",
                "doc_read_lines",
                "doc_list_in_folder",
                "doc_list_folders",
                "doc_grep_path",
                "doc_write",
                "doc_edit",
                "doc_replace_lines",
                "doc_move",
                "doc_copy",
                "doc_delete",
                "workflow_list",
                "workflow_get",
                "workflow_runs",
                "workflow_run_get",
                "workflow_start",
                "workflow_control",
                "workflow_breakpoints",
                "workflow_simulate",
                "workflow_restart",
                "work_file_read",
                "work_file_list",
                "work_file_find",
                "work_file_grep",
                "work_file_head_tail",
                "work_file_count",
                "work_file_write",
                "work_file_edit",
                "work_exec_run",
                "work_exec_status",
                "work_exec_tail",
                "work_exec_stat",
                "work_exec_check",
                "work_exec_kill")) {
            Tool tool = mock(Tool.class);
            // Shared fixture: every test uses a different subset of the
            // surface tools, so the common stubbings are lenient.
            lenient().when(tool.name()).thenReturn(name);
            lenient().when(tool.description()).thenReturn("stub " + name);
            lenient().when(tool.paramsSchema()).thenReturn(Map.of());
            out.put(name, tool);
        }
        return out;
    }

    private static String toolsListBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    }

    private static String callBody(String tool, Map<String, Object> args) {
        String arguments = new ObjectMapper().writeValueAsString(args);
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(McpProtocol.Outcome out) {
        assertThat(out.body()).isNotNull();
        assertThat(out.body()).doesNotContainKey("error");
        return (Map<String, Object>) out.body().get("result");
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolNamesOf(McpProtocol.Outcome out) {
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) resultOf(out).get("tools");
        return tools.stream().map(t -> (String) t.get("name")).toList();
    }

    private static boolean isErrorOf(McpProtocol.Outcome out) {
        return Boolean.TRUE.equals(resultOf(out).get("isError"));
    }

    @SuppressWarnings("unchecked")
    private static String textOf(McpProtocol.Outcome out) {
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) resultOf(out).get("content");
        return (String) content.get(0).get("text");
    }

    @Test
    void toolsCall_carriesTheCredentialsProjectConfinementIntoTheToolContext() {
        entry(Mode.RO, List.of());
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("content", "x"));
        McpCaller pinned = new McpCaller(McpCaller.Kind.INTEGRATION, "tok-1", USER, PROJECT);
        McpProjectAccessConfig.Entry resolved = accessService.resolveEntry(TENANT, PROJECT, TOKEN);
        when(accessService.resolveEntry(TENANT, PROJECT, pinned)).thenReturn(resolved);

        service.handle(callBody("doc_read", Map.of("path", "spec/design.md")), TENANT, PROJECT, pinned);

        ArgumentCaptor<ToolInvocationContext> captor = ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(tools.get("doc_read")).invoke(anyMap(), captor.capture());
        assertThat(captor.getValue().confinedToProject()).isEqualTo(PROJECT);
    }
}
