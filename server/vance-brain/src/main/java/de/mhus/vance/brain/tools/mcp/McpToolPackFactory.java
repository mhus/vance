package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.mcp.McpConnection;
import de.mhus.vance.toolpack.mcp.McpPackBuilder;
import de.mhus.vance.brain.oauth.OAuthDisconnectedEvent;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
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
        return create(document, /*ctx*/ null);
    }

    @Override
    public Collection<Tool> create(
            ServerToolDocument document,
            @org.jspecify.annotations.Nullable ToolInvocationContext ctx) {
        // Carry the caller's invocation scope into the bootstrap so the
        // MCP initialize/tools-list calls go out with the right user
        // identity. User-scoped templates ({{secret:user:oauth.x.access_token}})
        // need a non-null userId on the ctx — otherwise the secret
        // resolver returns empty and the remote MCP server 401s before
        // we even hear about tools.
        ToolInvocationContext bootstrap = new ToolInvocationContext(
                resolveTenantId(document, ctx),
                ctx == null ? document.getProjectId() : firstNonBlank(ctx.projectId(), document.getProjectId()),
                ctx == null ? null : ctx.sessionId(),
                ctx == null ? null : ctx.processId(),
                ctx == null ? null : ctx.userId());
        McpConnection connection = pool.acquire(document, bootstrap);
        Set<String> labels = document.getLabels() == null
                ? Set.of() : new LinkedHashSet<>(document.getLabels());
        McpPackBuilder.PackInput input = new McpPackBuilder.PackInput(
                document.getName(),
                labels,
                document.isPrimary(),
                document.isDefaultDeferred(),
                document.getPromptHint() == null ? "" : document.getPromptHint(),
                document.getParameters());
        Collection<Tool> tools = McpPackBuilder.buildWithConnection(input, connection, bootstrap);
        log.info("McpToolPackFactory pack='{}' tenant='{}' project='{}' user='{}' produced {} tools (transport={})",
                document.getName(), document.getTenantId(), document.getProjectId(),
                bootstrap.userId() == null ? "?" : bootstrap.userId(),
                tools.size(), guessTransportFor(document));
        return List.copyOf(tools);
    }

    private static String resolveTenantId(
            ServerToolDocument document, @org.jspecify.annotations.Nullable ToolInvocationContext ctx) {
        if (ctx != null && ctx.tenantId() != null && !ctx.tenantId().isBlank()) return ctx.tenantId();
        return document.getTenantId() == null ? "" : document.getTenantId();
    }

    private static @org.jspecify.annotations.Nullable String firstNonBlank(
            @org.jspecify.annotations.Nullable String a, @org.jspecify.annotations.Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
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

    /**
     * Called by the OAuth disconnect path so the user's open MCP
     * connections drop alongside the freshly-erased tokens. Without
     * this the cached connection keeps a Bearer header that resolves
     * to empty on the next call, surfacing as a confusing 401 instead
     * of a clean "user must reconnect".
     */
    public void invalidateForUser(String tenantId, String userId) {
        pool.invalidateForUser(tenantId, userId);
    }

    @EventListener
    void onOAuthDisconnect(OAuthDisconnectedEvent ev) {
        invalidateForUser(ev.tenantId(), ev.userId());
    }

    @PreDestroy
    void shutdown() {
        pool.close();
    }
}
