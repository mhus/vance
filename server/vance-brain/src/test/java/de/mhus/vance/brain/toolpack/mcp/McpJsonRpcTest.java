package de.mhus.vance.brain.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.toolpack.core.McpJsonRpc;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpJsonRpcTest {

    private final McpJsonRpc rpc = new McpJsonRpc();

    @Test
    void buildRequest_emitsValidJsonRpcEnvelope() {
        String body = rpc.buildRequest(7, "tools/list", Map.of("cursor", "abc"));

        assertThat(body).contains("\"jsonrpc\":\"2.0\"")
                .contains("\"id\":7")
                .contains("\"method\":\"tools/list\"")
                .contains("\"cursor\":\"abc\"");
    }

    @Test
    void buildNotification_omitsId() {
        String body = rpc.buildNotification("notifications/initialized", null);

        assertThat(body).contains("\"jsonrpc\":\"2.0\"")
                .contains("\"method\":\"notifications/initialized\"")
                .doesNotContain("\"id\"");
    }

    @Test
    void parse_request_returnsRequestFrame() {
        McpJsonRpc.Frame frame = McpJsonRpc.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"x\":1}}");

        assertThat(frame).isInstanceOf(McpJsonRpc.Frame.Request.class);
        McpJsonRpc.Frame.Request r = (McpJsonRpc.Frame.Request) frame;
        assertThat(r.id()).isEqualTo(3L);
        assertThat(r.method()).isEqualTo("tools/call");
        assertThat(r.params()).containsEntry("x", 1L);
    }

    @Test
    void parse_response_returnsResponseFrame() {
        McpJsonRpc.Frame frame = McpJsonRpc.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"ok\":true}}");

        assertThat(frame).isInstanceOf(McpJsonRpc.Frame.Response.class);
        McpJsonRpc.Frame.Response r = (McpJsonRpc.Frame.Response) frame;
        assertThat(r.id()).isEqualTo(5L);
        assertThat(r.error()).isNull();
        assertThat(r.result()).isInstanceOf(Map.class);
    }

    @Test
    void parse_errorResponse_returnsErrorMap() {
        McpJsonRpc.Frame frame = McpJsonRpc.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32601,\"message\":\"unknown\"}}");

        assertThat(frame).isInstanceOf(McpJsonRpc.Frame.Response.class);
        McpJsonRpc.Frame.Response r = (McpJsonRpc.Frame.Response) frame;
        assertThat(r.error()).isNotNull().containsEntry("code", -32601L);
    }

    @Test
    void parse_notification_omitsId() {
        McpJsonRpc.Frame frame = McpJsonRpc.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");

        assertThat(frame).isInstanceOf(McpJsonRpc.Frame.Notification.class);
        McpJsonRpc.Frame.Notification n = (McpJsonRpc.Frame.Notification) frame;
        assertThat(n.method()).isEqualTo("notifications/tools/list_changed");
    }

    @Test
    void parse_malformed_throws() {
        assertThatThrownBy(() -> McpJsonRpc.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpJsonRpc.parse("{\"jsonrpc\":\"2.0\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither method nor id");
    }

    @Test
    void allocId_isMonotonic() {
        long a = rpc.allocId();
        long b = rpc.allocId();
        long c = rpc.allocId();

        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }
}
