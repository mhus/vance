package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Wraps an {@link McpTransport} with the MCP protocol logic the
 * factory cares about: initialize handshake, cached
 * {@code tools/list}, {@code tools/call} dispatch, and
 * {@code notifications/tools/list_changed} subscription.
 *
 * <p>Lifecycle: {@link #ensureInitialized} brings the connection
 * up lazily — no work happens until the first call. After that the
 * tools list is cached until either {@link #invalidateTools} is
 * triggered (manually or by a {@code list_changed} notification) or
 * the connection is closed.
 *
 * <p>One {@link McpConnection} per {@link de.mhus.vance.shared.servertool.ServerToolDocument}
 * id; lifetime is owned by {@link McpConnectionPool}.
 */
@Slf4j
public final class McpConnection implements AutoCloseable {

    /** MCP protocol version this transport speaks (2025-spec date-string). */
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final McpConfig config;
    private final McpTransport transport;
    private final ToolInvocationContext bootstrapCtx;
    private final Object initLock = new Object();

    private volatile boolean initialized;
    private final AtomicReference<List<McpToolMeta>> toolsCache = new AtomicReference<>();
    private final Instant materializedAt;

    public McpConnection(McpConfig config, McpTransport transport, ToolInvocationContext bootstrapCtx) {
        this.config = config;
        this.transport = transport;
        this.bootstrapCtx = bootstrapCtx;
        this.materializedAt = Instant.now();
    }

    /** Bootstrap context used for the initialize handshake (auth headers, …). */
    public ToolInvocationContext bootstrapContext() {
        return bootstrapCtx;
    }

    /** Timestamp the connection was created — pool uses this to compare against doc.updatedAt. */
    public Instant materializedAt() {
        return materializedAt;
    }

    /**
     * Returns the cached tools list, initializing the connection on
     * first call. Subsequent calls return the same snapshot until
     * {@link #invalidateTools} fires.
     */
    public List<McpToolMeta> listTools(ToolInvocationContext ctx) {
        ensureInitialized(ctx);
        List<McpToolMeta> hit = toolsCache.get();
        if (hit != null) return hit;
        synchronized (initLock) {
            hit = toolsCache.get();
            if (hit != null) return hit;
            Object result = transport.sendRequest(
                    "tools/list",
                    Map.of(),
                    Duration.ofSeconds(config.timeoutSeconds()),
                    ctx);
            List<McpToolMeta> tools = McpToolMeta.fromListResult(result);
            toolsCache.set(tools);
            log.info("MCP tools/list: {} tools available (transport={})",
                    tools.size(), config.transport());
            return tools;
        }
    }

    /**
     * Dispatches an MCP {@code tools/call}. The result map is what the
     * MCP server returns ({@code content}, {@code isError}, …) — the
     * dispatcher-side {@code McpEndpointTool} hands it through to the
     * LLM unchanged.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(
            String toolName, Map<String, Object> arguments, ToolInvocationContext ctx) {
        ensureInitialized(ctx);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Object result = transport.sendRequest(
                "tools/call",
                params,
                Duration.ofSeconds(config.timeoutSeconds()),
                ctx);
        if (result instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("result", result);
        return wrapped;
    }

    /**
     * Drops the cached tools list — next {@link #listTools} re-fetches.
     * Triggered by {@code notifications/tools/list_changed} or by
     * the pool when an admin forces a refresh.
     */
    public void invalidateTools() {
        toolsCache.set(null);
    }

    @Override
    public void close() {
        transport.close();
        toolsCache.set(null);
        initialized = false;
    }

    private void ensureInitialized(ToolInvocationContext ctx) {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;
            transport.open();
            transport.setNotificationHandler(this::onNotification);

            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.put("capabilities", Map.of());
            initParams.put("clientInfo", Map.of(
                    "name", "vance-brain",
                    "version", "1.0.0"));

            try {
                transport.sendRequest(
                        "initialize",
                        initParams,
                        Duration.ofSeconds(config.initTimeoutSeconds()),
                        ctx);
                transport.sendNotification("notifications/initialized", null, ctx);
            } catch (RuntimeException e) {
                transport.close();
                throw new IllegalStateException(
                        "MCP initialize failed (" + config.transport() + "): " + e.getMessage(), e);
            }
            initialized = true;
            log.info("MCP connection initialized — transport={} ", config.transport());
        }
    }

    private void onNotification(McpJsonRpc.Frame.Notification n) {
        if (n == null || n.method() == null) return;
        if ("notifications/tools/list_changed".equals(n.method())) {
            log.info("MCP server signalled tools/list_changed — invalidating cache");
            invalidateTools();
        } else {
            log.debug("MCP unhandled notification method={}", n.method());
        }
    }

    /**
     * Tests / debug helper. Avoid in production paths — exposing the
     * transport bypasses the protocol guarantees ({@code initialize}
     * handshake, notifications routing).
     */
    @Nullable McpTransport transportForTesting() {
        return transport;
    }
}
