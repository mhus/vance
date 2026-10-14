package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SecretResolver secretResolver;
    private final @Nullable SessionGuard guard;
    private final Object initLock = new Object();

    private volatile boolean initialized;
    private final AtomicReference<List<McpToolMeta>> toolsCache = new AtomicReference<>();
    private final Instant materializedAt;

    public McpConnection(McpConfig config, McpTransport transport, ToolInvocationContext bootstrapCtx) {
        this(config, transport, bootstrapCtx, null);
    }

    /**
     * @param secretResolver used to render {@code defaultArgs} templates
     *                       on each {@code tools/call}. {@code null}
     *                       falls back to {@link SecretResolver#PASSTHROUGH},
     *                       which leaves templates verbatim — fine when
     *                       no defaults are configured.
     */
    public McpConnection(
            McpConfig config,
            McpTransport transport,
            ToolInvocationContext bootstrapCtx,
            @Nullable SecretResolver secretResolver) {
        this(config, transport, bootstrapCtx, secretResolver, null);
    }

    /**
     * @param guard host lifecycle hooks (brain: lease-backed;
     *              {@code null} = no guard, e.g. foot). See {@link SessionGuard}.
     */
    public McpConnection(
            McpConfig config,
            McpTransport transport,
            ToolInvocationContext bootstrapCtx,
            @Nullable SecretResolver secretResolver,
            @Nullable SessionGuard guard) {
        this.config = config;
        this.transport = transport;
        this.bootstrapCtx = bootstrapCtx;
        this.secretResolver = secretResolver == null ? SecretResolver.PASSTHROUGH : secretResolver;
        this.guard = guard;
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
            Object result =
                    transport.sendRequest("tools/list", Map.of(), Duration.ofSeconds(config.timeoutSeconds()), ctx);
            List<McpToolMeta> tools = McpToolMeta.fromListResult(result);
            toolsCache.set(tools);
            log.info("MCP tools/list: {} tools available (transport={})", tools.size(), config.transport());
            return tools;
        }
    }

    /**
     * Dispatches an MCP {@code tools/call}. The result map is what the
     * MCP server returns ({@code content}, {@code isError}, …) — the
     * dispatcher-side {@code McpEndpointTool} hands it through to the
     * LLM unchanged.
     *
     * <p>Argument-merge rule: {@code config.defaultArgs()} fills in keys
     * the LLM didn't supply (resolving {@code {{secret:…}}} templates
     * against the current invocation context). LLM-supplied values win
     * on key collision — defaults never override an explicit argument.
     * Empty resolved values are dropped (a blank cloud_id is worse than
     * an absent one for most MCP servers).
     *
     * <p>The merged map then passes through {@link McpArgumentCoercer},
     * so everything reaching the wire matches the types the tool's
     * {@code inputSchema} declares. MCP servers validate strictly: a
     * model that stringifies a boolean gets a {@code -32602} type error
     * instead of a result, and typically retries the identical call
     * before dropping the parameter. Operator-supplied
     * {@code defaultArgs} are always strings and benefit likewise.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments, ToolInvocationContext ctx) {
        ensureInitialized(ctx);
        if (guard != null) {
            try {
                guard.onSessionUse();
            } catch (McpSessionRevokedException e) {
                // The claim is gone: drop the session so the next call opens
                // a new one instead of failing on this one forever.
                close();
                throw e;
            }
        }
        Map<String, Object> mergedArgs =
                McpArgumentCoercer.coerce(mergeDefaultArgs(toolName, arguments, ctx), schemaFor(toolName));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", mergedArgs);
        Object result = transport.sendRequest("tools/call", params, Duration.ofSeconds(config.timeoutSeconds()), ctx);
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

    /**
     * Claim identity of the current session ({@link SessionGuard#sessionToken()}),
     * {@code null} without a guard or an open session.
     */
    public @Nullable String sessionToken() {
        return guard == null ? null : guard.sessionToken();
    }

    /**
     * Closes the session. The connection stays usable: the next call runs
     * the initialize handshake again. Serialized with that handshake so a
     * close never interleaves with a half-opened session.
     */
    @Override
    public void close() {
        synchronized (initLock) {
            try {
                transport.close();
            } finally {
                if (guard != null) guard.onSessionClose();
                toolsCache.set(null);
                initialized = false;
            }
        }
    }

    private void ensureInitialized(ToolInvocationContext ctx) {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;
            // Guard first: a busy lease aborts the session before anything is
            // opened — no half-initialized connection may exist.
            if (guard != null) guard.onSessionOpen();

            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.put("capabilities", Map.of());
            initParams.put(
                    "clientInfo",
                    Map.of(
                            "name", "vance-brain",
                            "version", "1.0.0"));

            // Everything after the guard's open sits in one try: any failure
            // — transport open, handshake, the guard's session-id hook —
            // must hand the claim back, or it stays booked until its TTL.
            try {
                transport.open();
                transport.setNotificationHandler(this::onNotification);
                transport.sendRequest("initialize", initParams, Duration.ofSeconds(config.initTimeoutSeconds()), ctx);
                transport.sendNotification("notifications/initialized", null, ctx);
                if (guard != null) guard.onSessionId(transport.currentSessionId());
            } catch (RuntimeException e) {
                transport.close();
                if (guard != null) guard.onSessionClose();
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

    private Map<String, Object> mergeDefaultArgs(
            String toolName, @Nullable Map<String, Object> llmArgs, ToolInvocationContext ctx) {
        Map<String, String> defaults = config.defaultArgs();
        if (defaults == null || defaults.isEmpty()) {
            return llmArgs == null ? Map.of() : llmArgs;
        }
        // Only inject defaults the tool's input-schema actually accepts.
        // Atlassian Remote MCP, for example, exposes a discovery tool
        // (`getAccessibleAtlassianResources`) that takes zero args —
        // appending a cloudId there yields a generic Atlassian server
        // error. Schema-driven filtering means a per-pack defaultArgs
        // entry safely covers ALL tools without poisoning the few that
        // don't accept it.
        Set<String> allowed = allowedArgKeys(toolName);
        Map<String, Object> out = new LinkedHashMap<>();
        if (llmArgs != null) out.putAll(llmArgs);
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (!allowed.contains(e.getKey())) continue;
            String resolved = secretResolver.resolveForConnector(e.getValue(), ctx);
            if (resolved == null || resolved.isEmpty()) continue;
            // Defaults-win on key collision. defaultArgs is meant for
            // tech-identity values (cloudId, tenant id, …) that the
            // server treats as authoritative. Letting the model override
            // means a hallucinated UUID can slip through and cause an
            // opaque server error. If a tool truly needs an LLM-supplied
            // alternative, omit the key from defaultArgs.
            out.put(e.getKey(), resolved);
        }
        return out;
    }

    /**
     * The tool's input-schema from the cached {@code tools/list}, or
     * {@code null} when the tool hasn't been listed yet — coercion then
     * has nothing to go on and leaves the arguments alone.
     */
    private @Nullable Map<String, Object> schemaFor(String toolName) {
        List<McpToolMeta> tools = toolsCache.get();
        if (tools == null) return null;
        for (McpToolMeta meta : tools) {
            if (meta.name().equals(toolName)) return meta.inputSchema();
        }
        return null;
    }

    /**
     * Returns the property names declared in the tool's input-schema —
     * the set of arguments the MCP server has agreed to accept. When the
     * schema is unknown (tool not yet listed) or has no properties node,
     * returns an empty set: better to skip an inject than to send an arg
     * the server may reject.
     */
    @SuppressWarnings("unchecked")
    private Set<String> allowedArgKeys(String toolName) {
        List<McpToolMeta> tools = toolsCache.get();
        if (tools == null) return Set.of();
        for (McpToolMeta meta : tools) {
            if (!meta.name().equals(toolName)) continue;
            Object props = meta.inputSchema().get("properties");
            if (!(props instanceof Map<?, ?> map)) return Set.of();
            Set<String> out = new java.util.LinkedHashSet<>();
            for (Object k : map.keySet()) {
                if (k != null) out.add(String.valueOf(k));
            }
            return out;
        }
        return Set.of();
    }

    /**
     * Tests / debug helper. Avoid in production paths — exposing the
     * transport bypasses the protocol guarantees ({@code initialize}
     * handshake, notifications routing).
     */
    @Nullable
    McpTransport transportForTesting() {
        return transport;
    }
}
