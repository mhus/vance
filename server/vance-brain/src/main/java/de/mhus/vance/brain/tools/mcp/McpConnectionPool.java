package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.mcp.McpConnection;
import de.mhus.vance.toolpack.mcp.McpHttpTransport;
import de.mhus.vance.toolpack.mcp.McpStdioTransport;
import de.mhus.vance.toolpack.mcp.McpTransport;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@code (documentId, tenantId, userId)} {@link McpConnection} cache.
 * User-scoped because MCP servers that authenticate via OAuth (Atlassian
 * Remote MCP, Slack MCP, …) issue session ids bound to the initiating
 * user's token — sharing a single connection across users would mix
 * sessions and the per-call bearer header could disagree with the
 * server's recorded user. Each user therefore gets their own connection.
 *
 * <p>Three flavours of invalidation are needed:
 * <ul>
 *   <li>{@link #invalidate(String) by document}: server-tool config
 *       changed or was removed — drops every per-user entry for that doc.</li>
 *   <li>{@link #invalidateForUser(String, String) by user}: OAuth
 *       disconnect — drops every connection that authenticated against
 *       that user's tokens, so the next call has to obtain a fresh one
 *       with (presumably) refreshed credentials.</li>
 *   <li>{@link #close} on shutdown — closes everything.</li>
 * </ul>
 *
 * <p>In-memory only; no persistent state. JVM restart drops every
 * subprocess and live HTTP session — {@link McpConnection#ensureInitialized}
 * re-spawns lazily on the next call.
 */
@Slf4j
public final class McpConnectionPool implements AutoCloseable {

    private final PackHttpClient httpClient;
    private final SecretResolver secretResolver;

    /** Keyed by {@code docId|tenantId|userId}. Anonymous / bootstrap calls keyed with empty user. */
    private final ConcurrentHashMap<String, McpConnection> entries = new ConcurrentHashMap<>();

    public McpConnectionPool(PackHttpClient httpClient, SecretResolver secretResolver) {
        this.httpClient = httpClient;
        this.secretResolver = secretResolver == null ? SecretResolver.NOOP : secretResolver;
    }

    /**
     * Returns a {@link McpConnection} for the {@code (doc, user)} pair.
     * Reuses the cached connection when one exists for the calling user;
     * otherwise builds a fresh one. Documents without an id (in-memory
     * tests) skip the cache — each call builds a fresh connection.
     */
    public synchronized McpConnection acquire(ServerToolDocument doc, ToolInvocationContext ctx) {
        if (doc.getId() == null) {
            return build(doc, ctx);
        }
        String key = entryKey(doc.getId(), ctx);
        McpConnection hit = entries.get(key);
        if (hit != null) {
            return hit;
        }
        McpConnection fresh = build(doc, ctx);
        entries.put(key, fresh);
        return fresh;
    }

    /**
     * Drops every cached connection for {@code docId} (all users); closes
     * each transport. Called by the registry when the server-tool config
     * is replaced or removed.
     */
    public synchronized void invalidate(String docId) {
        String prefix = docId + "|";
        List<String> matching = new ArrayList<>();
        for (String k : entries.keySet()) {
            if (k.startsWith(prefix)) matching.add(k);
        }
        for (String key : matching) {
            closeAndForget(key);
        }
    }

    /**
     * Drops every cached connection that belongs to {@code (tenantId, userId)}.
     * Invoked by the OAuth disconnect path so the user's MCP sessions
     * don't outlive their tokens — the next call rebuilds with whatever
     * credentials are configured now (typically nothing, surfacing as
     * a clean 401 "user must reconnect").
     */
    public synchronized void invalidateForUser(String tenantId, String userId) {
        String suffix = "|" + nullSafe(tenantId) + "|" + nullSafe(userId);
        List<String> matching = new ArrayList<>();
        for (String k : entries.keySet()) {
            if (k.endsWith(suffix)) matching.add(k);
        }
        for (String key : matching) {
            closeAndForget(key);
        }
        if (!matching.isEmpty()) {
            log.info("McpConnectionPool: invalidated {} connection(s) for tenant='{}' user='{}'",
                    matching.size(), tenantId, userId);
        }
    }

    @Override
    public synchronized void close() {
        for (McpConnection c : entries.values()) {
            try { c.close(); }
            catch (RuntimeException ex) {
                log.warn("McpConnectionPool: error closing connection on shutdown: {}", ex.toString());
            }
        }
        entries.clear();
    }

    private void closeAndForget(String key) {
        McpConnection hit = entries.remove(key);
        if (hit != null) {
            try { hit.close(); }
            catch (RuntimeException e) {
                log.warn("McpConnectionPool: error closing connection '{}': {}", key, e.toString());
            }
        }
    }

    private McpConnection build(ServerToolDocument doc, ToolInvocationContext ctx) {
        McpConfig cfg = McpConfig.fromParameters(doc.getParameters());
        McpJsonRpc rpc = new McpJsonRpc();
        McpTransport transport = switch (cfg.transport()) {
            case STDIO -> new McpStdioTransport(cfg, rpc);
            case HTTP -> new McpHttpTransport(cfg, rpc, httpClient, secretResolver);
        };
        return new McpConnection(cfg, transport, ctx, secretResolver);
    }

    private static String entryKey(String docId, @Nullable ToolInvocationContext ctx) {
        String tenant = ctx == null ? "" : nullSafe(ctx.tenantId());
        String user = ctx == null ? "" : nullSafe(ctx.userId());
        return docId + "|" + tenant + "|" + user;
    }

    private static String nullSafe(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** Visible for tests — current cache contents. */
    Map<String, McpConnection> entriesSnapshot() {
        return Map.copyOf(entries);
    }
}
