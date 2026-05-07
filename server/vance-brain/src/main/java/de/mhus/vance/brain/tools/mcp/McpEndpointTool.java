package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.brain.toolpack.mcp.McpConnection;
import de.mhus.vance.brain.toolpack.mcp.McpToolMeta;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.Map;
import java.util.Set;

/**
 * Single MCP-server tool exposed as a Vance {@link Tool}. Built per
 * sub-tool by {@link McpToolPackFactory}. Tool name follows the
 * {@code <packName>__<mcpToolName>} pack-naming convention.
 */
final class McpEndpointTool implements Tool {

    private final String fullName;
    private final McpToolMeta meta;
    private final Set<String> labels;
    private final boolean deferred;
    private final boolean primary;
    private final String searchHint;
    private final McpConnection connection;

    McpEndpointTool(
            String fullName,
            McpToolMeta meta,
            Set<String> labels,
            boolean deferred,
            boolean primary,
            String searchHint,
            McpConnection connection) {
        this.fullName = fullName;
        this.meta = meta;
        this.labels = labels == null ? Set.of() : labels;
        this.deferred = deferred;
        this.primary = primary;
        this.searchHint = searchHint == null ? "" : searchHint;
        this.connection = connection;
    }

    @Override public String name() { return fullName; }

    @Override public String description() {
        return meta.description() == null || meta.description().isBlank()
                ? "MCP tool " + meta.name() : meta.description();
    }

    @Override public boolean primary() { return primary; }
    @Override public boolean deferred() { return deferred; }
    @Override public String searchHint() { return searchHint; }
    @Override public Set<String> labels() { return labels; }
    @Override public Map<String, Object> paramsSchema() { return meta.inputSchema(); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return connection.callTool(meta.name(), params == null ? Map.of() : params, ctx);
    }
}
