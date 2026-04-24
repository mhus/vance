package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
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

    public ClientToolSource(ClientToolRegistry registry, ClientToolChannel channel) {
        this.registry = registry;
        this.channel = channel;
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
                return pending.future().get(INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
}
