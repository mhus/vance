package de.mhus.vance.brain.servertool;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolSource;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Server-side {@link ToolSource} backed by {@link ServerToolService}'s
 * cascade ({@code project → _vance → built-in beans}). The full
 * cascade lives in the service; this source is a thin adapter that
 * binds the request {@link ToolInvocationContext} to the service.
 *
 * <p>Registered before {@code ClientToolSource} so client-pushed tools
 * cannot shadow server-managed tools that share a name (the dispatcher
 * resolves first-wins on name collisions).
 *
 * <p>Tools issued for an invocation context without a project fall
 * back to the {@code _vance} system project — this matches the
 * fall-through behaviour of {@code DocumentService.lookupCascade}.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class ConfiguredToolSource implements ToolSource {

    public static final String SOURCE_ID = "server";

    private final ServerToolService service;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<Tool> tools(ToolInvocationContext ctx) {
        // Thread the caller's invocation context all the way down: user-
        // scoped MCP / REST factories bootstrap connections at first
        // materialise, and they need ctx.userId() to resolve user-scoped
        // secret templates (OAuth access tokens). Without this the
        // remote MCP server 401s on initialize, see McpToolPackFactory.
        return service.listAll(ctx.tenantId(), effectiveProjectId(ctx), ctx);
    }

    @Override
    public Optional<Tool> find(String name, ToolInvocationContext ctx) {
        return service.lookup(ctx.tenantId(), effectiveProjectId(ctx), name, ctx);
    }

    private static String effectiveProjectId(ToolInvocationContext ctx) {
        String pid = ctx.projectId();
        return (pid == null || pid.isBlank())
                ? de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME
                : pid;
    }
}
