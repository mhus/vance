package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.mcp.McpConnection;
import de.mhus.vance.toolpack.mcp.McpPackBuilder;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side {@link ToolFactory} for {@code type: mcp_server}.
 * Maintains a per-doc-id {@link McpConnectionPool} so MCP connections
 * survive across cascade reads (one initialized session per pack-doc,
 * recycled when {@code updatedAt} changes). Materialisation logic
 * delegates to the pure-Java {@link McpPackBuilder} in vance-toolpack
 * — same code path the foot-side registry uses.
 *
 * <p>See {@code planning/server-tool-providers.md} §4.3 for the
 * recipe-side YAML schema.
 */
@Component
@Slf4j
public class McpToolPackFactory implements ToolFactory {

    public static final String TYPE_ID = "mcp_server";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "transport", Map.of("type", "string", "enum", List.of("stdio", "http"),
                            "description", "Transport type — stdio (subprocess) or http (Streamable HTTP)."),
                    "command", Map.of("type", "array",
                            "description", "stdio: argv to spawn (e.g. ['npx','@modelcontextprotocol/server-x'])"),
                    "url", Map.of("type", "string",
                            "description", "http: single Streamable-HTTP endpoint."),
                    "auth", Map.of("type", "object",
                            "description", "Optional auth — bearer/basic/apiKey for the HTTP transport."),
                    "tls", Map.of("type", "object",
                            "description", "TLS settings for the HTTP transport.")));

    private final McpConnectionPool pool;

    public McpToolPackFactory(SettingsSecretResolver secretResolver) {
        this.pool = new McpConnectionPool(new PackHttpClient(), secretResolver);
    }

    @Override public String typeId() { return TYPE_ID; }
    @Override public Map<String, Object> parametersSchema() { return PARAMETERS_SCHEMA; }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        ToolInvocationContext bootstrap = new ToolInvocationContext(
                document.getTenantId() == null ? "" : document.getTenantId(),
                document.getProjectId(),
                /*sessionId*/ null,
                /*processId*/ null,
                /*userId*/ null);
        McpConnection connection = pool.acquire(document, bootstrap);
        Set<String> labels = document.getLabels() == null
                ? Set.of() : new LinkedHashSet<>(document.getLabels());
        McpPackBuilder.PackInput input = new McpPackBuilder.PackInput(
                document.getName(),
                labels,
                document.isPrimary(),
                document.isDefaultDeferred(),
                document.getParameters());
        Collection<Tool> tools = McpPackBuilder.buildWithConnection(input, connection, bootstrap);
        log.info("McpToolPackFactory pack='{}' tenant='{}' project='{}' produced {} tools (transport={})",
                document.getName(), document.getTenantId(), document.getProjectId(), tools.size(),
                guessTransportFor(document));
        return List.copyOf(tools);
    }

    private static String guessTransportFor(ServerToolDocument doc) {
        Object t = doc.getParameters() == null ? null : doc.getParameters().get("transport");
        return t == null ? "?" : String.valueOf(t).toLowerCase(Locale.ROOT);
    }

    @Override
    public void invalidate(@org.jspecify.annotations.Nullable String documentId) {
        if (documentId == null) return;
        pool.invalidate(documentId);
    }

    @PreDestroy
    void shutdown() {
        pool.close();
    }
}
