package de.mhus.vance.foot.tools;

import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.tools.ClientToolRegisterRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import java.time.Duration;
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
 * <p>Lifecycle:
 * <ol>
 *   <li>Spring discovers all {@link ClientTool} beans at startup; the
 *       registry is built and indexed by name.</li>
 *   <li>On session-bind ({@code SessionService.bind}) the service
 *       sends one {@code client-tool-register} envelope to the brain
 *       with the full spec list.</li>
 *   <li>The brain stores the registration per-session and starts
 *       routing matching tool calls our way.</li>
 *   <li>Incoming {@code client-tool-invoke} envelopes go through
 *       {@link #dispatch}, which runs the {@link ClientSecurityService}
 *       gate and the local implementation, returning the
 *       {@link ClientToolInvokeResponse} for the
 *       {@link de.mhus.vance.foot.connection.handlers.ClientToolInvokeHandler}
 *       to ship back.</li>
 * </ol>
 *
 * <p>Re-registration is triggered automatically on every bind. A
 * client-only {@code /tools-register} command also exists for manual
 * trigger — useful while developing tools.
 */
@Service
@Slf4j
public class ClientToolService {

    private final Map<String, ClientTool> byName;
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

    public ClientToolService(
            List<ClientTool> tools,
            ClientSecurityService security,
            ObjectProvider<ConnectionService> connectionProvider) {
        this.byName = tools.stream().collect(Collectors.toMap(
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
        log.info("ClientToolService — {} tool(s): {}", byName.size(), byName.keySet());
    }

    /** Registered tool names, sorted, for diagnostic listings. */
    public List<String> toolNames() {
        return byName.keySet().stream().sorted().toList();
    }

    /** Direct lookup — returns {@code null} when no tool of that name is registered. */
    public @org.jspecify.annotations.Nullable ClientTool find(String name) {
        return byName.get(name);
    }

    /**
     * Announces every registered tool to the brain. Safe to call when
     * disconnected (becomes a no-op); concurrent calls are coalesced
     * via {@link #registering}.
     */
    public void registerAll() {
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
            List<ToolSpec> specs = byName.values().stream()
                    .map(ClientTool::toSpec)
                    .toList();
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
            log.info("client-tool-register: announced {} tools to brain", specs.size());
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
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        ClientTool tool = byName.get(toolName);
        if (tool == null) {
            return error(correlationId, "Unknown client tool: " + toolName);
        }
        if (!security.permit(toolName, safeParams)) {
            return error(correlationId, security.denyReason(toolName, safeParams));
        }
        try {
            Map<String, Object> result = tool.invoke(safeParams);
            return ClientToolInvokeResponse.builder()
                    .correlationId(correlationId)
                    .result(result == null ? new LinkedHashMap<>() : result)
                    .build();
        } catch (RuntimeException e) {
            log.warn("ClientTool '{}' threw: {}", toolName, e.toString());
            return error(correlationId, "Tool failed: " + e.getMessage());
        }
    }

    private static ClientToolInvokeResponse error(String correlationId, String message) {
        return ClientToolInvokeResponse.builder()
                .correlationId(correlationId)
                .error(message)
                .build();
    }
}
