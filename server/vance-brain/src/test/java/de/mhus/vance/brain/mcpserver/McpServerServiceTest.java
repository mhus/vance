package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class McpServerServiceTest {

    @Mock ServerToolService serverToolService;
    @Mock ToolDispatcher toolDispatcher;

    McpServerService service;

    @BeforeEach
    void setUp() {
        service = new McpServerService(serverToolService, toolDispatcher, new ObjectMapper());
    }

    @Test
    void initialize_echoesRequestedProtocolVersion_andAdvertisesTools() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";

        McpServerService.Outcome out = service.handle(body, "acme", "_tenant", "_showcase");

        Map<String, Object> result = resultOf(out);
        assertThat(result).containsEntry("protocolVersion", "2025-06-18");
        assertThat(result).containsKey("capabilities");
        assertThat(result).containsKey("serverInfo");
    }

    @Test
    void initialize_withoutParams_fallsBackToServerProtocolVersion() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";

        McpServerService.Outcome out = service.handle(body, "acme", "_tenant", "_showcase");

        assertThat(resultOf(out)).containsEntry("protocolVersion", McpServerService.PROTOCOL_VERSION);
    }

    @Test
    void toolsList_mapsCatalogueToMcpToolShape() {
        Tool tool = stubTool("doc_create", "Create a document",
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", List.of("path")));
        when(serverToolService.listAll(eq("acme"), eq("proj"), any(ToolInvocationContext.class)))
                .thenReturn(List.of(tool));

        McpServerService.Outcome out = service.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "acme", "proj", "_showcase");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) resultOf(out).get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("name", "doc_create");
        assertThat(tools.get(0)).containsEntry("description", "Create a document");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tools.get(0).get("inputSchema");
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
    }

    @Test
    void toolsList_normalizesTypelessSchemaToObject() {
        Tool tool = stubTool("noop", "No params", Map.of());
        when(serverToolService.listAll(any(), any(), any())).thenReturn(List.of(tool));

        McpServerService.Outcome out = service.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}",
                "acme", "proj", "_showcase");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) resultOf(out).get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tools.get(0).get("inputSchema");
        assertThat(schema).containsEntry("type", "object").containsKey("properties");
    }

    @Test
    void toolsCall_wrapsSuccessfulResultAsTextContent() {
        when(toolDispatcher.invoke(eq("doc_create"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("id", "abc", "path", "notes.md"));

        String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"doc_create\",\"arguments\":{\"path\":\"notes.md\"}}}";
        McpServerService.Outcome out = service.handle(body, "acme", "proj", "_showcase");

        Map<String, Object> result = resultOf(out);
        assertThat(result).containsEntry("isError", false);
        assertThat(textContent(result)).contains("abc").contains("notes.md");
    }

    @Test
    void toolsCall_surfacesToolExceptionAsIsErrorContent() {
        when(toolDispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new ToolException("document_locked: write blocked"));

        String body = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"doc_write\",\"arguments\":{}}}";
        McpServerService.Outcome out = service.handle(body, "acme", "proj", "_showcase");

        Map<String, Object> result = resultOf(out);
        assertThat(result).containsEntry("isError", true);
        assertThat(textContent(result)).contains("document_locked");
    }

    @Test
    void toolsCall_missingName_returnsInvalidParamsError() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{}}";

        McpServerService.Outcome out = service.handle(body, "acme", "proj", "_showcase");

        assertThat(errorCodeOf(out)).isEqualTo(-32602);
    }

    @Test
    void unknownMethod_returnsMethodNotFound() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}";

        McpServerService.Outcome out = service.handle(body, "acme", "proj", "_showcase");

        assertThat(errorCodeOf(out)).isEqualTo(-32601);
    }

    @Test
    void notification_isAcknowledgedWithNoContent() {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

        McpServerService.Outcome out = service.handle(body, "acme", "proj", "_showcase");

        assertThat(out.noContent()).isTrue();
        assertThat(out.body()).isNull();
    }

    @Test
    void malformedJson_returnsParseError() {
        McpServerService.Outcome out = service.handle("{not json", "acme", "proj", "_showcase");

        assertThat(errorCodeOf(out)).isEqualTo(-32700);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(McpServerService.Outcome out) {
        assertThat(out.body()).isNotNull();
        assertThat(out.body()).doesNotContainKey("error");
        return (Map<String, Object>) out.body().get("result");
    }

    private static int errorCodeOf(McpServerService.Outcome out) {
        assertThat(out.body()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) out.body().get("error");
        assertThat(error).isNotNull();
        return ((Number) error.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static String textContent(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return (String) content.get(0).get("text");
    }

    private static Tool stubTool(String name, String description, Map<String, Object> schema) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public boolean primary() {
                return true;
            }

            @Override
            public Map<String, Object> paramsSchema() {
                return schema;
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
                return Map.of();
            }
        };
    }
}
