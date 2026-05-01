package de.mhus.vance.brain.permission;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single-line façade for inbound code: turn the transport (HTTP request /
 * WebSocket connection) into a {@link SecurityContext} and call
 * {@link PermissionService#enforce}. Inbound classes inject this and never
 * touch {@link SecurityContextFactory} or {@link PermissionService} directly.
 *
 * <p>Use {@link #enforce(SecurityContext, Resource, Action)} when you already
 * have a context — typically inside a service that received one as a
 * parameter, or for {@link SecurityContext#SYSTEM} background work.
 */
@Component
@RequiredArgsConstructor
public class RequestAuthority {

    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    public void enforce(HttpServletRequest request, Resource resource, Action action) {
        permissionService.enforce(contextFactory.fromRequest(request), resource, action);
    }

    public void enforce(ConnectionContext connection, Resource resource, Action action) {
        permissionService.enforce(contextFactory.fromConnection(connection), resource, action);
    }

    public void enforce(SecurityContext context, Resource resource, Action action) {
        permissionService.enforce(context, resource, action);
    }

    public boolean check(HttpServletRequest request, Resource resource, Action action) {
        return permissionService.check(contextFactory.fromRequest(request), resource, action);
    }

    public boolean check(ConnectionContext connection, Resource resource, Action action) {
        return permissionService.check(contextFactory.fromConnection(connection), resource, action);
    }

    public SecurityContext contextOf(HttpServletRequest request) {
        return contextFactory.fromRequest(request);
    }

    public SecurityContext contextOf(ConnectionContext connection) {
        return contextFactory.fromConnection(connection);
    }
}
