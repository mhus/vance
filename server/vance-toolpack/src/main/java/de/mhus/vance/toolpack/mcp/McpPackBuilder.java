package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java orchestrator that turns an MCP-server pack specification
 * into a list of runnable {@link Tool}s. Connects to the MCP server,
 * runs the {@code initialize} handshake, fetches {@code tools/list},
 * and emits one {@link McpEndpointTool} per advertised tool.
 *
 * <p>Lives in vance-toolpack so server and foot share the same
 * orchestration. The connection itself is owned by the caller (server-
 * side via {@code McpConnectionPool}, foot-side via the registry's
 * own pool) — this builder just sequences initialize + tools/list +
 * tool wrapping.
 */
public final class McpPackBuilder {

    private McpPackBuilder() { /* static only */ }

    /** Pack input: same shape as {@link de.mhus.vance.toolpack.rest.RestApiPackBuilder.PackInput}. */
    public record PackInput(
            String name,
            Set<String> labels,
            boolean primary,
            boolean defaultDeferred,
            Map<String, Object> parameters) {
    }

    /**
     * Builds the pack. Opens the transport, initializes the MCP
     * session, loads {@code tools/list}, wraps each as a Vance
     * {@link Tool}.
     *
     * @param httpClient   shared HTTP client; required for HTTP transport
     *                     (ignored for stdio)
     * @param secretResolver resolves auth tokens from the host's secret store
     * @param bootstrapCtx context used for the initialize call (often
     *                     a synthetic foot-side scope; server-side
     *                     uses the spawning ToolInvocationContext)
     */
    public static Collection<Tool> build(
            PackInput input,
            PackHttpClient httpClient,
            SecretResolver secretResolver,
            ToolInvocationContext bootstrapCtx) {
        McpConfig cfg = McpConfig.fromParameters(input.parameters());
        McpJsonRpc rpc = new McpJsonRpc();
        McpTransport transport = switch (cfg.transport()) {
            case STDIO -> new McpStdioTransport(cfg, rpc);
            case HTTP -> new McpHttpTransport(cfg, rpc, httpClient, secretResolver);
        };
        McpConnection connection = new McpConnection(cfg, transport, bootstrapCtx);

        List<McpToolMeta> tools = connection.listTools(bootstrapCtx);

        Set<String> packLabels = input.labels() == null ? Set.of() : input.labels();
        List<Tool> out = new ArrayList<>(tools.size());
        for (McpToolMeta meta : tools) {
            String fullName = input.name() + "__" + meta.name();
            Set<String> labels = mergeLabels(packLabels, input.name());
            String description = meta.description() == null || meta.description().isBlank()
                    ? "MCP tool " + meta.name() : meta.description();
            String hint = description.length() <= 80 ? description
                    : description.substring(0, 77) + "...";
            out.add(new McpEndpointTool(
                    fullName, meta, labels, input.defaultDeferred(),
                    input.primary(), hint, connection));
        }
        return List.copyOf(out);
    }

    /**
     * Variant for callers that already own a live {@link McpConnection}
     * (server-side McpConnectionPool reuses connections across cascade
     * reads). Skips connection setup and reuses the supplied pool.
     */
    public static Collection<Tool> buildWithConnection(
            PackInput input,
            McpConnection connection,
            ToolInvocationContext bootstrapCtx) {
        List<McpToolMeta> tools = connection.listTools(bootstrapCtx);
        Set<String> packLabels = input.labels() == null ? Set.of() : input.labels();
        List<Tool> out = new ArrayList<>(tools.size());
        for (McpToolMeta meta : tools) {
            String fullName = input.name() + "__" + meta.name();
            Set<String> labels = mergeLabels(packLabels, input.name());
            String description = meta.description() == null || meta.description().isBlank()
                    ? "MCP tool " + meta.name() : meta.description();
            String hint = description.length() <= 80 ? description
                    : description.substring(0, 77) + "...";
            out.add(new McpEndpointTool(
                    fullName, meta, labels, input.defaultDeferred(),
                    input.primary(), hint, connection));
        }
        return List.copyOf(out);
    }

    private static Set<String> mergeLabels(Set<String> packLabels, String packName) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(packLabels);
        out.add("mcp");
        out.add("mcp:" + packName);
        out.add("side-effect");
        return Set.copyOf(out);
    }
}
