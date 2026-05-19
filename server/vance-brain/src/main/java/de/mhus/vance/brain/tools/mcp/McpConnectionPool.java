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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-document {@link McpConnection} cache. Keyed by the underlying
 * document id; values are reused across cascade reads. Invalidation is
 * explicit: {@code ServerToolRegistry} calls {@link #invalidate(String)}
 * when a server-tool config is replaced or removed, which closes the
 * stale transport and lets the next {@link #acquire} open a fresh one.
 *
 * <p>In-memory only; no persistent state. JVM restart drops every
 * subprocess and live HTTP session — {@link McpConnection#ensureInitialized}
 * re-spawns lazily on the next call.
 */
@Slf4j
public final class McpConnectionPool implements AutoCloseable {

    private final PackHttpClient httpClient;
    private final SecretResolver secretResolver;
    private final ConcurrentHashMap<String, McpConnection> entries = new ConcurrentHashMap<>();

    public McpConnectionPool(PackHttpClient httpClient, SecretResolver secretResolver) {
        this.httpClient = httpClient;
        this.secretResolver = secretResolver == null ? SecretResolver.NOOP : secretResolver;
    }

    /**
     * Returns a {@link McpConnection} for {@code doc}. Reuses the cached
     * connection when one exists for the document id; otherwise builds a
     * fresh one. Documents without an id (in-memory tests) skip the
     * cache — each call builds a fresh connection.
     */
    public synchronized McpConnection acquire(ServerToolDocument doc, ToolInvocationContext ctx) {
        if (doc.getId() == null) {
            return build(doc, ctx);
        }
        McpConnection hit = entries.get(doc.getId());
        if (hit != null) {
            return hit;
        }
        McpConnection fresh = build(doc, ctx);
        entries.put(doc.getId(), fresh);
        return fresh;
    }

    /** Drops the cached connection for {@code docId}; closes its transport. */
    public synchronized void invalidate(String docId) {
        McpConnection hit = entries.remove(docId);
        if (hit != null) {
            try { hit.close(); }
            catch (RuntimeException e) {
                log.warn("McpConnectionPool: error during invalidate close: {}", e.toString());
            }
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

    private McpConnection build(ServerToolDocument doc, ToolInvocationContext ctx) {
        McpConfig cfg = McpConfig.fromParameters(doc.getParameters());
        McpJsonRpc rpc = new McpJsonRpc();
        McpTransport transport = switch (cfg.transport()) {
            case STDIO -> new McpStdioTransport(cfg, rpc);
            case HTTP -> new McpHttpTransport(cfg, rpc, httpClient, secretResolver);
        };
        return new McpConnection(cfg, transport, ctx);
    }

    /** Visible for tests — current cache contents. */
    Map<String, McpConnection> entriesSnapshot() {
        return Map.copyOf(entries);
    }
}
