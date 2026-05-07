package de.mhus.vance.brain.toolpack.mcp;

import de.mhus.vance.brain.toolpack.core.McpJsonRpc;
import de.mhus.vance.brain.toolpack.core.PackHttpClient;
import de.mhus.vance.brain.toolpack.core.SecretResolver;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-document {@link McpConnection} cache. Keyed by Mongo doc-id;
 * value carries the cached connection plus the {@code updatedAt}
 * snapshot it was built from. A doc-edit (new {@code updatedAt})
 * triggers reconnect — the old transport is closed cleanly, a fresh
 * one is opened on the next acquire.
 *
 * <p>In-memory only; no persistent state. JVM restart drops every
 * subprocess and live HTTP session — {@link McpConnection#ensureInitialized}
 * re-spawns lazily on the next call.
 */
@Slf4j
public final class McpConnectionPool implements AutoCloseable {

    private final PackHttpClient httpClient;
    private final SecretResolver secretResolver;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public McpConnectionPool(PackHttpClient httpClient, SecretResolver secretResolver) {
        this.httpClient = httpClient;
        this.secretResolver = secretResolver == null ? SecretResolver.NOOP : secretResolver;
    }

    /**
     * Returns a {@link McpConnection} for {@code doc}. Reuses the cached
     * connection iff the doc hasn't been edited since it was opened.
     * Documents without an id (in-memory tests) skip the cache —
     * each call builds a fresh connection.
     */
    public synchronized McpConnection acquire(ServerToolDocument doc, ToolInvocationContext ctx) {
        if (doc.getId() == null) {
            return build(doc, ctx);
        }
        Instant ts = doc.getUpdatedAt() == null ? Instant.EPOCH : doc.getUpdatedAt();
        Entry hit = entries.get(doc.getId());
        if (hit != null && hit.updatedAt.equals(ts) && hit.connection.transportForTesting() != null) {
            return hit.connection;
        }
        if (hit != null) {
            try { hit.connection.close(); }
            catch (RuntimeException e) {
                log.warn("McpConnectionPool: error closing stale connection: {}", e.toString());
            }
        }
        McpConnection fresh = build(doc, ctx);
        entries.put(doc.getId(), new Entry(ts, fresh));
        return fresh;
    }

    /** Drops the cached connection for {@code docId}; closes its transport. */
    public synchronized void invalidate(String docId) {
        Entry hit = entries.remove(docId);
        if (hit != null) {
            try { hit.connection.close(); }
            catch (RuntimeException e) {
                log.warn("McpConnectionPool: error during invalidate close: {}", e.toString());
            }
        }
    }

    @Override
    public synchronized void close() {
        for (Entry e : entries.values()) {
            try { e.connection.close(); }
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
    Map<String, Entry> entriesSnapshot() {
        return Map.copyOf(entries);
    }

    record Entry(Instant updatedAt, McpConnection connection) { }
}
