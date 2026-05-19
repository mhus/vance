package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tool.promptHint() default + McpEndpointTool pass-through. The
 * pack-level hint travels from McpToolPackFactory → McpPackBuilder.PackInput
 * → McpEndpointTool wrapper; engines later read it via
 * ContextToolsApi.activePromptHints to inject it into the system prompt.
 */
class McpEndpointToolPromptHintTest {

    @Test
    void tool_interface_default_returns_empty_hint() {
        Tool plain = new Tool() {
            @Override public String name() { return "plain"; }
            @Override public String description() { return ""; }
            @Override public boolean primary() { return true; }
            @Override public boolean deferred() { return false; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(
                    Map<String, Object> p, de.mhus.vance.toolpack.ToolInvocationContext c) {
                return Map.of();
            }
        };
        assertThat(plain.promptHint()).isEmpty();
    }

    @Test
    void mcp_endpoint_tool_carries_pack_level_hint() {
        McpToolMeta meta = new McpToolMeta(
                "atlassian.search", "search Jira",
                Map.of("type", "object", "properties", Map.of()));
        McpEndpointTool tool = new McpEndpointTool(
                "jira__atlassian.search",
                meta,
                java.util.Set.of("jira"),
                /*deferred*/ true,
                /*primary*/ false,
                /*searchHint*/ "search Jira",
                "cloudId is auto-injected — do not set it yourself.",
                /*connection*/ null);

        assertThat(tool.promptHint())
                .isEqualTo("cloudId is auto-injected — do not set it yourself.");
    }

    @Test
    void mcp_endpoint_tool_back_compat_constructor_yields_empty_hint() {
        // Legacy 7-arg constructor (no promptHint) is retained for tests
        // / older call sites. McpPackBuilder uses the 8-arg form.
        McpToolMeta meta = new McpToolMeta(
                "x", "y", Map.of("type", "object", "properties", Map.of()));
        McpEndpointTool tool = new McpEndpointTool(
                "pack__x", meta, java.util.Set.of(),
                false, false, "hint", /*connection*/ null);

        assertThat(tool.promptHint()).isEmpty();
    }
}
