package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the MCP-STDIO subprocess environment filter (code-review Phase 2):
 * the Brain process's secrets must not leak into a spawned MCP server, only
 * a minimal non-secret base plus the pack's explicit env is passed.
 */
class McpStdioTransportEnvTest {

    @Test
    void childEnv_dropsSecrets_keepsPassthrough() {
        Map<String, String> inherited = Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/vance",
                "VANCE_ENCRYPTION_PASSWORD", "s3cr3t",
                "VANCE_MONGODB_URI", "mongodb://root:pw@host/db",
                "VANCE_INTERNAL_TOKEN", "tok");

        Map<String, String> child = McpStdioTransport.childEnv(inherited, Map.of());

        assertThat(child).containsEntry("PATH", "/usr/bin").containsEntry("HOME", "/home/vance");
        assertThat(child).doesNotContainKeys(
                "VANCE_ENCRYPTION_PASSWORD", "VANCE_MONGODB_URI", "VANCE_INTERNAL_TOKEN");
    }

    @Test
    void childEnv_packEnvIsApplied_andMayOverride() {
        Map<String, String> inherited = Map.of("PATH", "/usr/bin");

        Map<String, String> child = McpStdioTransport.childEnv(
                inherited, Map.of("PATH", "/opt/bin", "MCP_API_KEY", "k"));

        assertThat(child).containsEntry("PATH", "/opt/bin").containsEntry("MCP_API_KEY", "k");
    }
}
