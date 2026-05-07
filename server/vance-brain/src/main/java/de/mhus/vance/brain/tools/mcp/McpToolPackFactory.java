package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import de.mhus.vance.brain.toolpack.mcp.McpConnection;
import de.mhus.vance.brain.toolpack.mcp.McpConnectionPool;
import de.mhus.vance.brain.toolpack.mcp.McpToolMeta;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Multi-tool pack factory for {@code type: mcp_server}. Connects to
 * the configured MCP server (subprocess or HTTP), reads the
 * {@code tools/list}, and emits one {@link McpEndpointTool} per
 * advertised tool. Tool names follow {@code <packName>__<mcpToolName>}.
 *
 * <p>Connection lifecycle is owned by an internal
 * {@link McpConnectionPool} — one connection per
 * {@link ServerToolDocument#getId()}, reused across cascade reads
 * within the same JVM lifetime, recycled on doc edit
 * ({@code updatedAt} change).
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
        List<McpToolMeta> tools = connection.listTools(bootstrap);

        Set<String> docLabels = document.getLabels() == null
                ? Set.of() : new LinkedHashSet<>(document.getLabels());
        boolean defaultDeferred = document.isDefaultDeferred();

        List<Tool> out = new ArrayList<>(tools.size());
        for (McpToolMeta meta : tools) {
            String fullName = document.getName() + ToolFactory.PACK_SEPARATOR + meta.name();
            Set<String> labels = mergeLabels(docLabels, document.getName());
            String description = meta.description() == null || meta.description().isBlank()
                    ? "MCP tool " + meta.name() : meta.description();
            String hint = description.length() <= 80 ? description
                    : description.substring(0, 77) + "...";
            out.add(new McpEndpointTool(
                    fullName, meta, labels, defaultDeferred, document.isPrimary(),
                    hint, connection));
        }
        log.info("McpToolPackFactory pack='{}' tenant='{}' project='{}' produced {} tools (transport={})",
                document.getName(), document.getTenantId(), document.getProjectId(), out.size(),
                guessTransportFor(document));
        return List.copyOf(out);
    }

    private static Set<String> mergeLabels(Set<String> packLabels, String packName) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(packLabels);
        out.add("mcp");
        out.add("mcp:" + packName);
        // MCP tools default to side-effect — server-side tools commonly
        // mutate filesystem, query external services, etc. Recipes can
        // override per-pack via labelOverrides if a server's read-only
        // tools should be reachable in plan-mode.
        out.add("side-effect");
        return Set.copyOf(out);
    }

    private static String guessTransportFor(ServerToolDocument doc) {
        Object t = doc.getParameters() == null ? null : doc.getParameters().get("transport");
        return t == null ? "?" : String.valueOf(t).toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    void shutdown() {
        pool.close();
    }
}
