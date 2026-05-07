package de.mhus.vance.brain.execution;

import de.mhus.vance.brain.tools.client.ClientToolChannel;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.ExecStatTool;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes {@code stat / tail / kill} for an executionId to the side that
 * owns it: brain-local jobs go through {@link ExecManager}, foot-side
 * jobs go through {@link ClientToolRegistry} + {@link ClientToolChannel}
 * as a {@code client-tool-invoke} on the matching foot connection.
 *
 * <p>Tenant boundary: the registry entry's tenant must equal the
 * caller's tenant. Cross-tenant probing is rejected with a
 * {@link ToolException}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionRouter {

    private static final int FOOT_INVOCATION_TIMEOUT_SECONDS = 30;

    private final ExecutionRegistryService registry;
    private final ExecManager execManager;
    private final ClientToolRegistry clientToolRegistry;
    private final ClientToolChannel clientToolChannel;

    public Map<String, Object> stat(String executionId, String tenantId) {
        ExecutionRegistryEntry entry = require(executionId, tenantId);
        return switch (entry.owner()) {
            case ExecutionOwner.Brain brain -> ExecStatTool.render(
                    execManager.stat(tenantId, entry.projectId(), executionId)
                            .orElseThrow(() -> new ToolException(
                                    "Brain-side job vanished: '" + executionId + "'")));
            case ExecutionOwner.Foot foot -> invokeOnFoot(
                    foot.clientId(), "client_exec_stat",
                    Map.of("id", executionId));
        };
    }

    public Map<String, Object> tail(
            String executionId, String tenantId, int n, String streamName) {
        ExecutionRegistryEntry entry = require(executionId, tenantId);
        return switch (entry.owner()) {
            case ExecutionOwner.Brain brain -> renderTail(executionId,
                    execManager.tail(tenantId, entry.projectId(), executionId, n,
                            "stderr".equalsIgnoreCase(streamName)
                                    ? ExecManager.Stream.STDERR
                                    : ExecManager.Stream.STDOUT),
                    streamName);
            case ExecutionOwner.Foot foot -> invokeOnFoot(
                    foot.clientId(), "client_exec_tail",
                    Map.of("id", executionId, "n", n, "stream",
                            "stderr".equalsIgnoreCase(streamName) ? "stderr" : "stdout"));
        };
    }

    public Map<String, Object> kill(String executionId, String tenantId) {
        ExecutionRegistryEntry entry = require(executionId, tenantId);
        return switch (entry.owner()) {
            case ExecutionOwner.Brain brain -> {
                boolean killed = execManager.kill(tenantId, entry.projectId(), executionId);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", executionId);
                out.put("killed", killed);
                yield out;
            }
            case ExecutionOwner.Foot foot -> invokeOnFoot(
                    foot.clientId(), "client_exec_kill",
                    Map.of("id", executionId));
        };
    }

    private ExecutionRegistryEntry require(String executionId, String tenantId) {
        if (executionId == null || executionId.isBlank()) {
            throw new ToolException("'id' is required");
        }
        ExecutionRegistryEntry entry = registry.find(executionId).orElseThrow(() ->
                new ToolException("Unknown execution: '" + executionId + "'"));
        if (entry.tenantId() != null && !entry.tenantId().equals(tenantId)) {
            throw new ToolException(
                    "Execution '" + executionId + "' belongs to a different tenant");
        }
        return entry;
    }

    private Map<String, Object> invokeOnFoot(
            String clientId, String toolName, Map<String, Object> params) {
        ClientToolRegistry.Entry entry = clientToolRegistry.entryByConnection(clientId)
                .orElseThrow(() -> new ToolException(
                        "Foot client '" + clientId + "' is not connected — "
                                + "cannot route '" + toolName + "'"));
        ClientToolRegistry.Pending pending = clientToolRegistry.beginInvocation(
                resolveSessionId(entry), toolName);
        try {
            clientToolChannel.sendInvoke(
                    entry.wsSession(), pending.correlationId(), toolName, params);
            return pending.future().get(FOOT_INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            clientToolRegistry.cancel(pending.correlationId(),
                    toolName + " timed out after " + FOOT_INVOCATION_TIMEOUT_SECONDS + "s");
            throw new ToolException(toolName + " timed out");
        } catch (InterruptedException e) {
            clientToolRegistry.cancel(pending.correlationId(), "interrupted");
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for " + toolName);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ToolException(toolName + " failed: " + cause.getMessage(), cause);
        } catch (IOException | RuntimeException e) {
            clientToolRegistry.cancel(pending.correlationId(), e.getMessage());
            throw new ToolException(toolName + " dispatch failed: " + e.getMessage(), e);
        }
    }

    /**
     * The {@link ClientToolRegistry} pending map keys correlations by
     * sessionId; we don't have a sessionId here in the router context,
     * so use the connectionId as a stable label. Cleanup logic in the
     * registry only uses sessionId to fail pendings on session unbind,
     * which is exactly the semantic we want.
     */
    private static String resolveSessionId(ClientToolRegistry.Entry entry) {
        return entry.connectionId();
    }

    private static Map<String, Object> renderTail(
            String id, java.util.List<String> lines, String streamName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("stream", "stderr".equalsIgnoreCase(streamName) ? "stderr" : "stdout");
        out.put("lines", lines);
        out.put("returned", lines.size());
        return out;
    }

    /** Visible to tests. */
    Optional<ExecutionRegistryEntry> peek(String executionId) {
        return registry.find(executionId);
    }
}
