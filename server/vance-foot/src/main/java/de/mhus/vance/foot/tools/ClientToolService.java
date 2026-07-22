package de.mhus.vance.foot.tools;

import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.tools.ClientToolRegisterRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Local registry of {@link ClientTool} beans, plus the wire glue that
 * announces them to the brain and dispatches incoming invocations.
 *
 * <p>Two tool sources contribute names to the registry:
 * <ol>
 *   <li><b>Bean-scope:</b> {@link ClientTool} Spring components — the
 *       8 hand-coded foot-side capabilities (file/exec/javascript).
 *       Indexed at construction time, fixed for the JVM lifetime.</li>
 *   <li><b>Pack-scope:</b> {@link Tool}s materialised from
 *       {@code ~/.vance/foot-tools/*.json} by
 *       {@code FootToolPackRegistry}. Mutable — replaced on
 *       {@code /tools reload}.</li>
 * </ol>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>On session-bind ({@code SessionService.bind}) the service
 *       sends one {@code client-tool-register} envelope to the brain
 *       with the union of bean-scope + pack-scope tool specs.</li>
 *   <li>The brain stores the registration per-session and starts
 *       routing matching tool calls our way.</li>
 *   <li>Incoming {@code client-tool-invoke} envelopes go through
 *       {@link #dispatch}, which runs the {@link ClientSecurityService}
 *       gate and the local implementation.</li>
 * </ol>
 *
 * <p>Re-registration triggers automatically on every bind. A client-only
 * {@code /tools-register} command also exists for manual trigger;
 * {@code /tools reload} re-reads the JSON pack files first, then
 * re-registers.
 */
@Service
@Slf4j
public class ClientToolService {

    private final Map<String, ClientTool> beanByName;
    private final ClientSecurityService security;
    /**
     * Resolved lazily — {@link ConnectionService} → {@link
     * de.mhus.vance.foot.connection.MessageDispatcher} →
     * {@link de.mhus.vance.foot.connection.handlers.ClientToolInvokeHandler} →
     * this service. Eager injection would cycle at construction; the
     * provider defers the lookup to first use, by which time Spring
     * has the singleton ready.
     */
    private final ObjectProvider<ConnectionService> connectionProvider;
    private final AtomicBoolean registering = new AtomicBoolean();
    /**
     * Hard kill-switch flipped by {@code --no-tools}. When suppressed,
     * {@link #registerAll()} returns without sending and incoming
     * invocations from the brain are rejected with an error. Used for
     * {@code -w}/web profile foots that must not expose local resources.
     */
    private final AtomicBoolean suppressed = new AtomicBoolean(false);

    /**
     * Pack-derived tools — replaced wholesale by {@link #setPackTools}.
     * Concurrent because reads (dispatch / registerAll) overlap with
     * writes (boot-time loader thread, /tools reload).
     */
    private final Map<String, Tool> packByName = new ConcurrentHashMap<>();

    private final ObjectProvider<ClientToolPrettyRenderer> rendererProvider;

    public ClientToolService(
            List<ClientTool> tools,
            ClientSecurityService security,
            ObjectProvider<ConnectionService> connectionProvider,
            ObjectProvider<ClientToolPrettyRenderer> rendererProvider) {
        this.beanByName = tools.stream().collect(Collectors.toMap(
                ClientTool::name,
                t -> t,
                (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate ClientTool name: " + a.name()
                                    + " — " + a.getClass() + " vs " + b.getClass());
                },
                ConcurrentHashMap::new));
        this.security = security;
        this.connectionProvider = connectionProvider;
        this.rendererProvider = rendererProvider;
        log.info("ClientToolService — {} bean tool(s): {}", beanByName.size(), beanByName.keySet());
    }

    /** Registered tool names from bean + pack scope, sorted, for diagnostic listings. */
    public List<String> toolNames() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(beanByName.keySet());
        all.addAll(packByName.keySet());
        return List.copyOf(all);
    }

    /**
     * Snapshot of the current tool manifest as {@link ToolSpec}s — beans
     * first, then packs. Used by the daemon registration flow which has
     * to publish the same list as {@link #registerAll} would, but to a
     * different message type ({@code daemon-register}) outside the
     * session model.
     */
    public List<ToolSpec> manifestSnapshot() {
        List<ToolSpec> specs = new java.util.ArrayList<>(beanByName.size() + packByName.size());
        for (ClientTool t : beanByName.values()) specs.add(t.toSpec());
        for (Tool t : packByName.values()) specs.add(t.toSpec("client"));
        return specs;
    }

    /** Direct lookup across both scopes — pack tools win when names collide (rare). */
    public @org.jspecify.annotations.Nullable Tool find(String name) {
        Tool fromPack = packByName.get(name);
        if (fromPack != null) return fromPack;
        return beanByName.get(name);
    }

    /**
     * Bean-scope-only lookup. Used by the foot script executor: scripts
     * may only invoke the hand-coded {@link ClientTool} beans, not the
     * dynamically-loaded pack tools (REST / MCP) — that's a deliberate
     * trust boundary, scripts get the local capabilities, packs go
     * directly through brain-side LLM dispatch.
     */
    public @org.jspecify.annotations.Nullable ClientTool findBean(String name) {
        return beanByName.get(name);
    }

    /**
     * Gated in-JVM invocation for the client-side script bridge
     * ({@code client.tools.call(...)} in GraalJS). Applies the <b>same</b>
     * {@link ClientSecurityService} gate — and the {@code --no-tools}
     * kill-switch — as {@link #dispatch}, then runs the bean tool.
     *
     * <p>Security (code-review B5): the script bridge previously received
     * a raw {@link ClientTool} bean and invoked it directly, so
     * {@code client.tools.call('client_exec_run', …)} ran arbitrary local
     * commands around the Foot sandbox. All script-driven client-tool
     * calls must pass through here so the permission policy (deny/allow/ask
     * incl. the default-deny floor) is enforced exactly as for
     * brain-driven calls.
     *
     * @throws SecurityException if the tool is unknown, tools are
     *         suppressed, or the invocation is denied by the policy.
     */
    public Map<String, Object> invokeFromScript(
            String name, @org.jspecify.annotations.Nullable Map<String, Object> params) {
        if (suppressed.get()) {
            throw new SecurityException(
                    "Client tools are disabled on this foot (--no-tools / web profile)");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        ClientTool tool = findBean(name);
        if (tool == null) {
            throw new SecurityException("Unknown client tool: " + name);
        }
        if (!security.permit(name, safeParams)) {
            throw new SecurityException(security.denyReason(name, safeParams));
        }
        return tool.invoke(safeParams);
    }

    /**
     * Replaces the pack-scope tool list. Called by
     * {@code FootToolPackRegistry} after re-reading JSON pack files.
     * The previous pack list is dropped — callers that own life-cycle
     * resources (MCP connections, HTTP keep-alive) should close them
     * <i>before</i> calling this method.
     *
     * <p>Triggers a re-register against the brain if a session is
     * currently bound. Idempotent.
     */
    public void setPackTools(Collection<Tool> packTools) {
        Map<String, Tool> next = new ConcurrentHashMap<>();
        if (packTools != null) {
            for (Tool t : packTools) {
                Tool prev = next.putIfAbsent(t.name(), t);
                if (prev != null) {
                    log.warn("ClientToolService: pack tool name collision '{}' — keeping first; "
                            + "rejected: {}", t.name(), t.getClass().getName());
                }
                if (beanByName.containsKey(t.name())) {
                    log.warn("ClientToolService: pack tool '{}' shadows a bean tool of the same name "
                            + "— pack wins via find()/dispatch()", t.name());
                }
            }
        }
        packByName.clear();
        packByName.putAll(next);
        log.info("ClientToolService — pack tools: {} ({} bean total {})",
                packByName.size(), beanByName.size(), beanByName.size() + packByName.size());
        registerAll();
    }

    /**
     * Sets the kill-switch flag for this service. Wired to {@code --no-tools}
     * (and {@code -w} via that). Once on, neither registration nor
     * dispatch happens.
     */
    public void setSuppressed(boolean suppressed) {
        this.suppressed.set(suppressed);
    }

    /**
     * Announces every registered tool (bean + pack) to the brain.
     * Safe to call when disconnected (becomes a no-op); concurrent
     * calls are coalesced via {@link #registering}.
     */
    public void registerAll() {
        if (suppressed.get()) {
            log.debug("ClientToolService.registerAll — suppressed (--no-tools)");
            return;
        }
        ConnectionService connection = connectionProvider.getIfAvailable();
        if (connection == null || !connection.isOpen()) {
            log.debug("ClientToolService.registerAll — not connected, skipped");
            return;
        }
        if (!registering.compareAndSet(false, true)) {
            log.debug("ClientToolService.registerAll — another registration in flight, skipped");
            return;
        }
        try {
            List<ToolSpec> specs = new ArrayList<>(beanByName.size() + packByName.size());
            beanByName.values().forEach(t -> specs.add(t.toSpec()));
            packByName.values().forEach(t -> specs.add(t.toSpec("client")));
            ClientToolRegisterRequest request = ClientToolRegisterRequest.builder()
                    .tools(specs)
                    .build();
            // Brain replies with an empty body; we just need the ack to know
            // the registration landed.
            connection.request(
                    MessageType.CLIENT_TOOL_REGISTER,
                    request,
                    Object.class,
                    Duration.ofSeconds(10));
            log.info("client-tool-register: announced {} tools to brain ({} bean + {} pack)",
                    specs.size(), beanByName.size(), packByName.size());
        } catch (Exception e) {
            log.warn("client-tool-register failed: {}", e.toString());
        } finally {
            registering.set(false);
        }
    }

    /**
     * Resolves and runs an incoming brain-side invocation. Failures
     * are caught and returned as {@link ClientToolInvokeResponse#error}
     * so the brain always gets a reply (silence would block its tool
     * loop until our 30-second timeout).
     */
    public ClientToolInvokeResponse dispatch(
            String correlationId, String toolName, Map<String, Object> params) {
        if (suppressed.get()) {
            return error(correlationId,
                    "Client tools are disabled on this foot (--no-tools / web profile)");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        Tool tool = find(toolName);
        if (tool == null) {
            return error(correlationId, "Unknown client tool: " + toolName);
        }
        if (!security.permit(toolName, safeParams)) {
            return error(correlationId, security.denyReason(toolName, safeParams));
        }
        ClientToolPrettyRenderer renderer = rendererProvider.getIfAvailable();
        ClientToolPrettyRenderer.State renderState = renderer == null
                ? null
                : renderer.renderInvocation(toolName, safeParams);
        try {
            // Foot has no tenant/session/process state per dispatch — we
            // synthesise a thin context so the unified Tool interface
            // is satisfied. Pack tools that need real env vars resolve
            // via the EnvSecretResolver, not via this context.
            ToolInvocationContext ctx = bootstrapContext();
            Map<String, Object> result = tool.invoke(safeParams, ctx);
            Map<String, Object> safeResult = result == null ? new LinkedHashMap<>() : result;
            if (renderer != null) {
                renderer.renderResult(renderState, safeResult);
            }
            return ClientToolInvokeResponse.builder()
                    .correlationId(correlationId)
                    .result(safeResult)
                    .build();
        } catch (RuntimeException e) {
            log.warn("ClientTool '{}' threw: {}", toolName, e.toString());
            if (renderer != null) {
                renderer.renderError(renderState, e.getMessage());
            }
            return error(correlationId, "Tool failed: " + e.getMessage());
        }
    }

    private static ClientToolInvokeResponse error(String correlationId, String message) {
        return ClientToolInvokeResponse.builder()
                .correlationId(correlationId)
                .error(message)
                .build();
    }

    private static ToolInvocationContext bootstrapContext() {
        // The foot client side runs as a single user / single session;
        // tools rarely care about the scope record. tenantId is empty —
        // foot's bound session knows it but ClientToolService doesn't
        // need it for local invocation routing.
        return new ToolInvocationContext("", null, null, null, null);
    }
}
