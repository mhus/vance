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
 * Verifies that {@link McpConnection#callTool} conforms arguments to the
 * tool's {@code inputSchema} before they reach the wire — MCP servers
 * validate strictly and answer a stringified boolean with
 * {@code -32602}, not a result.
 */
class McpConnectionCoercionTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "p", "sess", "proc", "wile.coyote");

    private static final List<Map<String, Object>> CATALOG = List.of(
            Map.of(
                    "name", "press_key",
                    "description", "Press a key",
                    "inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "key", Map.of("type", "string"),
                                    "includeSnapshot", Map.of("type", "boolean")))));

    @Test
    void stringifiedBoolean_isCoercedBeforeSend() {
        RecordingTransport transport = new RecordingTransport();
        McpConnection conn = connection(transport, Map.of());
        conn.listTools(CTX);  // production path always lists before calling

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "Enter");
        args.put("includeSnapshot", "true");
        conn.callTool("press_key", args, CTX);

        assertThat(transport.lastArguments())
                .containsEntry("key", "Enter")
                .containsEntry("includeSnapshot", Boolean.TRUE);
    }

    @Test
    void stringDefaultArg_isCoercedToo() {
        // defaultArgs come from operator YAML as strings; the tool still
        // wants the declared type on the wire.
        RecordingTransport transport = new RecordingTransport();
        McpConnection conn = connection(transport, Map.of("includeSnapshot", "false"));
        conn.listTools(CTX);

        conn.callTool("press_key", Map.of("key", "Enter"), CTX);

        assertThat(transport.lastArguments()).containsEntry("includeSnapshot", Boolean.FALSE);
    }

    @Test
    void unlistedTool_sendsArgumentsUnchanged() {
        // No cached schema → nothing to coerce towards. The call still
        // goes out; the server decides.
        RecordingTransport transport = new RecordingTransport();
        McpConnection conn = connection(transport, Map.of());

        conn.callTool("press_key", Map.of("includeSnapshot", "true"), CTX);

        assertThat(transport.lastArguments()).containsEntry("includeSnapshot", "true");
    }

    private static McpConnection connection(
            McpTransport transport, Map<String, String> defaultArgs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("transport", "http");
        params.put("url", "http://x/mcp");
        if (!defaultArgs.isEmpty()) {
            params.put("defaultArgs", defaultArgs);
        }
        return new McpConnection(
                McpConfig.fromParameters(params), transport, CTX, SecretResolver.PASSTHROUGH);
    }

    /** Captures the last {@code tools/call} arguments map. */
    private static class RecordingTransport implements McpTransport {
        private @Nullable Map<String, Object> lastArguments;
        private boolean open;

        Map<String, Object> lastArguments() {
            return lastArguments == null ? Map.of() : lastArguments;
        }

        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public boolean isOpen() { return open; }

        @Override
        @SuppressWarnings("unchecked")
        public Object sendRequest(String method, @Nullable Map<String, Object> params,
                                  Duration timeout, ToolInvocationContext ctx) {
            if ("tools/call".equals(method) && params != null
                    && params.get("arguments") instanceof Map<?, ?> m) {
                lastArguments = new LinkedHashMap<>((Map<String, Object>) m);
            }
            if ("initialize".equals(method)) {
                return Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of());
            }
            if ("tools/list".equals(method)) {
                return Map.of("tools", CATALOG);
            }
            return Map.of();
        }

        @Override
        public void sendNotification(String method, @Nullable Map<String, Object> params,
                                     ToolInvocationContext ctx) {
            // no-op
        }

        @Override
        public void setNotificationHandler(
                @Nullable Consumer<McpJsonRpc.Frame.Notification> handler) {
            // no-op
        }
    }
}
