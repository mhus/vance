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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class McpProjectServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "showcase";
    private static final String USER = "_agent";

    @Mock
    McpProjectAccessService accessService;

    McpProjectService service;
    Map<String, Tool> tools;

    @BeforeEach
    void setUp() {
        tools = stubTools();
        McpProjectToolCatalog catalog = new McpProjectToolCatalog(new ArrayList<>(tools.values()));
        service = new McpProjectService(accessService, catalog, new ObjectMapper());
    }

    // ──────────────────── tools/list ────────────────────

    @Test
    void toolsList_withoutMatchingEntry_isEmptyCatalogue() {
        when(accessService.resolveEntry(TENANT, PROJECT, null)).thenReturn(null);

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, USER, null);

        assertThat(toolNamesOf(out)).isEmpty();
    }

    @Test
    void toolsList_roEntry_listsOnlyReadTools() {
        entry(Mode.RO, List.of());

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, USER, "tok-1");

        assertThat(toolNamesOf(out))
                .containsExactly(
                        "doc_read", "doc_read_lines", "doc_list_in_folder", "doc_list_folders", "doc_grep_path");
    }

    @Test
    void toolsList_rwEntry_listsReadAndWriteTools() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(toolsListBody(), TENANT, PROJECT, USER, "tok-1");

        assertThat(toolNamesOf(out)).hasSize(8).contains("doc_read", "doc_write");
    }

    // ──────────────────── tools/call: access ────────────────────

    @Test
    void toolsCall_withoutMatchingEntry_isError() {
        when(accessService.resolveEntry(TENANT, PROJECT, "tok-1")).thenReturn(null);

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("mcp-access.yaml");
    }

    @Test
    void toolsCall_unknownTool_isError() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out =
                service.handle(callBody("doc_delete", Map.of("path", "spec/x.md")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("Unknown tool");
    }

    @Test
    void toolsCall_writeToolUnderRoMode_isError() {
        entry(Mode.RO, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "spec/x.md", "content", "hi")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'ro'");
        verify(tools.get("doc_write"), never()).invoke(anyMap(), any());
    }

    // ──────────────────── tools/call: surface confinement ────────────────────

    @Test
    void toolsCall_idAddressing_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("id", "6626abc")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("'path' only");
    }

    @Test
    void toolsCall_foreignProjectId_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_read", Map.of("projectId", "other", "path", "spec/x.md")),
                TENANT,
                PROJECT,
                USER,
                "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("pinned to project '" + PROJECT + "'");
    }

    @Test
    void toolsCall_pathOutsidePrefixes_isRejected() {
        entry(Mode.RW, List.of("spec/"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "readme/x.md")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("outside the prefixes");
        verify(tools.get("doc_read"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_missingPathScopeUnderPrefixes_isRejected() {
        entry(Mode.RO, List.of("spec/"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_grep_path", Map.of("pattern", "foo")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("path-scoped");
    }

    @Test
    void toolsCall_wildcardScopeUnderPrefixes_isRejected() {
        entry(Mode.RO, List.of("spec/"));

        McpProtocol.Outcome out = service.handle(
                callBody("doc_list_in_folder", Map.of("pathPrefix", "*")), TENANT, PROJECT, USER, "tok-1");

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
                USER,
                "tok-1");

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
                USER,
                "tok-1");

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
                USER,
                "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
        verify(tools.get("doc_write"), never()).invoke(anyMap(), any());
    }

    @Test
    void toolsCall_writeToBareVanceFolder_isRejected() {
        entry(Mode.RW, List.of());

        McpProtocol.Outcome out = service.handle(
                callBody("doc_write", Map.of("path", "/_vance/", "content", "x")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("never writable");
    }

    // ──────────────────── tools/call: happy path & failures ────────────────────

    @Test
    void toolsCall_allowedPath_invokesToolAndReturnsResult() {
        entry(Mode.RW, List.of("spec/"));
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("path", "spec/design.md", "content", "hello"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/design.md")), TENANT, PROJECT, USER, "tok-1");

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
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("not found").contains("check the path");
    }

    @Test
    void toolsCall_runtimeException_isWrappedAsToolFailure() {
        entry(Mode.RW, List.of());
        when(tools.get("doc_read").invoke(anyMap(), any(ToolInvocationContext.class)))
                .thenThrow(new IllegalStateException("boom"));

        McpProtocol.Outcome out =
                service.handle(callBody("doc_read", Map.of("path", "spec/x.md")), TENANT, PROJECT, USER, "tok-1");

        assertThat(isErrorOf(out)).isTrue();
        assertThat(textOf(out)).contains("failed");
    }

    // ──────────────────── helpers ────────────────────

    private void entry(McpProjectAccessConfig.Mode mode, List<String> paths) {
        McpProjectAccessConfig.Entry entry = new McpProjectAccessConfig.Entry("test", "tok-1", mode, paths);
        when(accessService.resolveEntry(TENANT, PROJECT, "tok-1")).thenReturn(entry);
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
                "doc_replace_lines")) {
            Tool tool = mock(Tool.class);
            // Shared fixture: every test uses a different subset of the
            // eight tools, so the common stubbings are lenient.
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
}
