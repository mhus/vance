package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code defaultArgs} merge contract on
 * {@link McpConnection#callTool}: defaults fill in absent keys, LLM
 * arguments win on collision, secret templates resolve at call time,
 * empty resolutions are dropped.
 */
class McpConnectionDefaultArgsTest {

    private static final ToolInvocationContext CTX = ctx();

    @Test
    void default_args_fill_in_absent_keys() {
        RecordingTransport transport = new RecordingTransport();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp",
                "defaultArgs", Map.of("cloudId", "FIXED-CLOUD")));
        McpConnection conn = new McpConnection(cfg, transport, CTX, SecretResolver.NOOP);
        conn.listTools(CTX);  // warm schema cache — production path always does this first

        conn.callTool("jira.search", Map.of("jql", "project = FOO"), CTX);

        Map<String, Object> args = transport.lastArguments();
        assertThat(args)
                .containsEntry("jql", "project = FOO")
                .containsEntry("cloudId", "FIXED-CLOUD");
    }

    @Test
    void default_wins_on_key_collision() {
        // defaultArgs are tech-identity (cloudId, tenant id, …) and the
        // model is not allowed to override them — observed in production
        // with Gemini hallucinating Atlassian cloudIds, causing opaque
        // 500-style errors from the remote MCP. If a tool genuinely
        // needs an LLM-supplied alternative, omit the key from defaultArgs.
        RecordingTransport transport = new RecordingTransport();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp",
                "defaultArgs", Map.of("cloudId", "AUTHORITATIVE")));
        McpConnection conn = new McpConnection(cfg, transport, CTX, SecretResolver.NOOP);
        conn.listTools(CTX);

        conn.callTool("jira.search", Map.of("cloudId", "MODEL-HALLUCINATION"), CTX);

        assertThat(transport.lastArguments()).containsEntry("cloudId", "AUTHORITATIVE");
    }

    @Test
    void secret_template_is_resolved_before_send() {
        RecordingTransport transport = new RecordingTransport();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp",
                "defaultArgs", Map.of("cloudId", "{{secret:user:oauth.atlassian.cloud_id}}")));
        // Stub resolver returns the literal cloudId we expect on the wire.
        SecretResolver resolver = (input, ctx) ->
                "{{secret:user:oauth.atlassian.cloud_id}}".equals(input) ? "abc-123" : input;
        McpConnection conn = new McpConnection(cfg, transport, CTX, resolver);
        conn.listTools(CTX);  // warm schema cache

        conn.callTool("jira.search", Map.of("jql", "x"), CTX);

        assertThat(transport.lastArguments()).containsEntry("cloudId", "abc-123");
    }

    @Test
    void empty_resolution_drops_the_key() {
        // The resolver substitutes null/empty when no setting matches.
        // A blank cloudId is worse than an absent one — most MCP servers
        // 4xx on empty pre-conditions but treat absent ones as "ask the
        // model again". Drop the entry.
        RecordingTransport transport = new RecordingTransport();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp",
                "defaultArgs", Map.of("cloudId", "{{secret:user:oauth.atlassian.cloud_id}}")));
        SecretResolver resolver = (input, ctx) -> "";  // simulates no setting found

        McpConnection conn = new McpConnection(cfg, transport, CTX, resolver);
        conn.listTools(CTX);  // warm schema cache

        conn.callTool("jira.search", Map.of("jql", "x"), CTX);

        assertThat(transport.lastArguments())
                .containsEntry("jql", "x")
                .doesNotContainKey("cloudId");
    }

    @Test
    void default_args_are_skipped_for_tools_whose_schema_does_not_declare_them() {
        // Atlassian's getAccessibleAtlassianResources takes zero args.
        // Injecting cloudId there yielded a generic "trouble completing
        // this action" error in production. Schema-aware filtering means
        // a defaultArgs entry only flows into tools that actually accept it.
        RecordingTransport transport = new RecordingTransport();
        transport.setCatalog(java.util.List.of(
                Map.of(
                        "name", "getAccessibleAtlassianResources",
                        "description", "discovery",
                        "inputSchema", Map.of("type", "object", "properties", Map.of()))));
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp",
                "defaultArgs", Map.of("cloudId", "must-not-leak")));
        McpConnection conn = new McpConnection(cfg, transport, CTX, SecretResolver.NOOP);
        conn.listTools(CTX);

        conn.callTool("getAccessibleAtlassianResources", Map.of(), CTX);

        assertThat(transport.lastArguments())
                .as("schema declares no properties → cloudId default must be dropped")
                .doesNotContainKey("cloudId")
                .isEmpty();
    }

    @Test
    void no_default_args_passes_llm_arguments_untouched() {
        RecordingTransport transport = new RecordingTransport();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://x/mcp"));
        McpConnection conn = new McpConnection(cfg, transport, CTX, SecretResolver.NOOP);
        conn.listTools(CTX);  // warm schema cache — production path always does this first

        conn.callTool("jira.search", Map.of("jql", "issue=1"), CTX);

        assertThat(transport.lastArguments())
                .hasSize(1)
                .containsEntry("jql", "issue=1");
    }

    // ───── Helpers ─────

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext("acme", "p", "sess", "proc", "wile.coyote");
    }

    /** Captures the last {@code tools/call} arguments map. */
    private static class RecordingTransport implements McpTransport {
        private @Nullable Map<String, Object> lastArguments;
        private boolean open;
        /** tools/list response — defaults to one tool whose schema accepts cloudId+jql. */
        private List<Map<String, Object>> toolsCatalog = List.of(
                Map.of(
                        "name", "jira.search",
                        "description", "search",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cloudId", Map.of("type", "string"),
                                        "jql", Map.of("type", "string")))));

        Map<String, Object> lastArguments() {
            return lastArguments == null ? Map.of() : lastArguments;
        }

        void setCatalog(List<Map<String, Object>> tools) {
            this.toolsCatalog = tools;
        }

        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public boolean isOpen() { return open; }

        @Override
        @SuppressWarnings("unchecked")
        public Object sendRequest(String method, @Nullable Map<String, Object> params,
                                  Duration timeout, ToolInvocationContext ctx) {
            if ("tools/call".equals(method) && params != null) {
                Object args = params.get("arguments");
                if (args instanceof Map<?, ?> m) {
                    lastArguments = new LinkedHashMap<>((Map<String, Object>) m);
                }
            }
            if ("initialize".equals(method)) {
                return Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of());
            }
            if ("tools/list".equals(method)) {
                return Map.of("tools", toolsCatalog);
            }
            return Map.of();
        }

        @Override
        public void sendNotification(String method, @Nullable Map<String, Object> params,
                                     ToolInvocationContext ctx) {
            // no-op
        }

        @Override
        public void setNotificationHandler(@Nullable Consumer<McpJsonRpc.Frame.Notification> handler) {
            // no-op
        }
    }
}
