package de.mhus.vance.brain.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpConfigTest {

    @Test
    void stdioRequiresCommand() {
        assertThatThrownBy(() -> McpConfig.fromParameters(Map.of(
                "transport", "stdio")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'command' is required");
    }

    @Test
    void httpRequiresEitherUrlOrPostSseSplit() {
        assertThatThrownBy(() -> McpConfig.fromParameters(Map.of(
                "transport", "http")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'url'");

        // Accepting Streamable-HTTP single endpoint:
        McpConfig single = McpConfig.fromParameters(Map.of(
                "transport", "http", "url", "https://mcp.example.com/mcp"));
        assertThat(single.transport()).isEqualTo(McpConfig.Transport.HTTP);
        assertThat(single.url()).isEqualTo("https://mcp.example.com/mcp");

        // Accepting legacy split:
        McpConfig split = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "postUrl", "https://mcp.example.com/messages",
                "sseUrl", "https://mcp.example.com/sse"));
        assertThat(split.postUrl()).isNotNull();
        assertThat(split.sseUrl()).isNotNull();

        // Forbidden: BOTH:
        assertThatThrownBy(() -> McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "https://x",
                "postUrl", "https://y/messages",
                "sseUrl", "https://y/sse")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void unknownTransport_throws() {
        assertThatThrownBy(() -> McpConfig.fromParameters(Map.of(
                "transport", "carrierpigeon")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stdioCommandPropagates() {
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "stdio",
                "command", List.of("python", "mcp_server.py"),
                "cwd", "/srv/mcp",
                "env", Map.of("LOG_LEVEL", "info")));

        assertThat(cfg.command()).containsExactly("python", "mcp_server.py");
        assertThat(cfg.cwd()).isEqualTo("/srv/mcp");
        assertThat(cfg.env()).containsEntry("LOG_LEVEL", "info");
    }

    @Test
    void timeoutDefaults_areSane() {
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "stdio",
                "command", List.of("noop")));

        assertThat(cfg.timeoutSeconds()).isEqualTo(McpConfig.DEFAULT_TIMEOUT_SECONDS);
        assertThat(cfg.initTimeoutSeconds()).isEqualTo(McpConfig.DEFAULT_INIT_TIMEOUT_SECONDS);
    }
}
