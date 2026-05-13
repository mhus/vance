package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Surfaces tools that a connected client registered for its session.
 * The {@link ToolInvocationContext}'s {@code sessionId} picks which
 * registration we serve; without one, no tools are visible.
 *
 * <p>Invocation blocks on a {@link java.util.concurrent.CompletableFuture}
 * that the {@code client-tool-result} handler completes. Bounded by
 * {@link #INVOCATION_TIMEOUT_SECONDS} so a silent client can't stall a
 * think-engine turn forever.
 */
@Component
@Slf4j
public class ClientToolSource implements ToolSource {

    public static final String SOURCE_ID = "client";
    static final int INVOCATION_TIMEOUT_SECONDS = 30;

    private final ClientToolRegistry registry;
    private final ClientToolChannel channel;
    private final ExecutionRegistryService executionRegistry;

    public ClientToolSource(
            ClientToolRegistry registry,
            ClientToolChannel channel,
            ExecutionRegistryService executionRegistry) {
        this.registry = registry;
        this.channel = channel;
        this.executionRegistry = executionRegistry;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<Tool> tools(ToolInvocationContext ctx) {
        if (ctx.sessionId() == null) return List.of();
        return registry.toolsFor(ctx.sessionId()).stream()
                .<Tool>map(spec -> new ClientTool(spec))
                .toList();
    }

    @Override
    public Optional<Tool> find(String name, ToolInvocationContext ctx) {
        if (ctx.sessionId() == null) return Optional.empty();
        return registry.find(ctx.sessionId(), name).map(ClientTool::new);
    }

    /**
     * Adapter that projects a {@link ToolSpec} onto the server-side
     * {@link Tool} interface. Inner class so all calls stay bound to
     * this source's registry and channel — no static state.
     */
    private final class ClientTool implements Tool {

        private final ToolSpec spec;

        ClientTool(ToolSpec spec) {
            this.spec = spec;
        }

        @Override
        public String name() {
            return spec.getName();
        }

        @Override
        public String description() {
            return spec.getDescription();
        }

        @Override
        public boolean primary() {
            return spec.isPrimary();
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return spec.getParamsSchema();
        }

        @Override
        public java.util.Set<String> labels() {
            // Reflect the client-pushed labels so server-side selectors
            // (recipes, Plan-Mode read-only filter) can find them.
            return spec.getLabels() == null
                    ? java.util.Set.of()
                    : java.util.Set.copyOf(spec.getLabels());
        }

        @Override
        public java.util.Set<String> allowedForProfile() {
            // Reflect the client-pushed profile restriction so server-side
            // dispatchers respect it (e.g. eddie-profile hubs cannot
            // route client-tool results — see eddie-engine.md §8.4).
            return spec.getAllowedProfiles() == null
                    ? java.util.Set.of()
                    : java.util.Set.copyOf(spec.getAllowedProfiles());
        }

        @Override
        public boolean deferred() {
            return spec.isDeferred();
        }

        @Override
        public String searchHint() {
            return spec.getSearchHint() == null ? "" : spec.getSearchHint();
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            String sessionId = ctx.sessionId();
            if (sessionId == null) {
                throw new ToolException(
                        "Client tool '" + name() + "' requires a session scope");
            }
            ClientToolRegistry.Entry entry = registry.entry(sessionId).orElseThrow(
                    () -> new ToolException(
                            "Client tool '" + name()
                                    + "' unavailable: no client registration for session '"
                                    + sessionId + "'"));
            ClientToolRegistry.Pending pending = registry.beginInvocation(sessionId, name());
            try {
                channel.sendInvoke(entry.wsSession(), pending.correlationId(), name(), params);
                Map<String, Object> result = pending.future().get(
                        INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                maybeAttachOwnerProcessId(name(), result, ctx.processId());
                return result;
            } catch (TimeoutException e) {
                registry.cancel(pending.correlationId(),
                        "Client tool '" + name() + "' timed out after "
                                + INVOCATION_TIMEOUT_SECONDS + "s");
                throw new ToolException(
                        "Client tool '" + name() + "' timed out");
            } catch (InterruptedException e) {
                registry.cancel(pending.correlationId(), "interrupted");
                Thread.currentThread().interrupt();
                throw new ToolException("Interrupted waiting for client tool '" + name() + "'");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new ToolException(
                        "Client tool '" + name() + "' failed: " + cause.getMessage(), cause);
            } catch (IOException e) {
                registry.cancel(pending.correlationId(), e.getMessage());
                throw new ToolException(
                        "Client tool '" + name() + "' dispatch failed: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                registry.cancel(pending.correlationId(), e.getMessage());
                throw new ToolException(
                        "Client tool '" + name() + "' dispatch failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Threads the brain-side caller's processId onto a foot-spawned
     * exec entry so the {@link ExecutionRegistryService} can later
     * dispatch {@code EXEC_FINISHED} / {@code EXEC_TIMEOUT} events to
     * the right inbox when the foot's ENDED frame arrives.
     *
     * <p>Only {@code client_exec_run} carries an executionId in its
     * result — other client tools are left untouched. The STARTED
     * frame arrives over the same WebSocket as the tool-result, in
     * order, so the registry entry exists by the time we get here.
     * On the unlikely race (entry already dropped, e.g. instant
     * finish + foot disconnect) the attach silently does nothing.
     */
    private void maybeAttachOwnerProcessId(
            String toolName, Map<String, Object> result, @Nullable String processId) {
        if (processId == null || processId.isBlank()) return;
        if (!"client_exec_run".equals(toolName)) return;
        if (result == null) return;
        Object rawId = result.get("id");
        if (!(rawId instanceof String execId) || execId.isBlank()) return;
        executionRegistry.attachProcessId(execId, processId);
    }
}
