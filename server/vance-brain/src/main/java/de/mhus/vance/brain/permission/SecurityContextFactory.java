package de.mhus.vance.brain.permission;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds {@link SecurityContext} instances from authenticated transports.
 *
 * <p>For HTTP requests the JWT claims have already been validated by
 * {@link AccessFilterBase} and attached as request attributes — we only read
 * them. For WebSocket frames the same data lives on
 * {@link ConnectionContext} (set during the handshake).
 *
 * <p>Team memberships are resolved via {@link TeamService} and cached on the
 * request so multi-check endpoints don't multiply the lookup. WebSocket
 * connections cache on the {@link ConnectionContext} attribute slot — see
 * {@link #fromConnection}.
 */
@Component
@RequiredArgsConstructor
public class SecurityContextFactory {

    static final String REQ_ATTR_CONTEXT = "vance.permission.context";

    private final TeamService teamService;

    public SecurityContext fromRequest(HttpServletRequest request) {
        Object cached = request.getAttribute(REQ_ATTR_CONTEXT);
        if (cached instanceof SecurityContext ctx) {
            return ctx;
        }
        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String tenantId = (String) request.getAttribute(AccessFilterBase.ATTR_TENANT_ID);
        if (username == null || tenantId == null) {
            throw new IllegalStateException(
                    "No authenticated user on request — BrainAccessFilter must run first");
        }
        SecurityContext ctx = SecurityContext.user(username, tenantId, resolveTeams(tenantId, username));
        request.setAttribute(REQ_ATTR_CONTEXT, ctx);
        return ctx;
    }

    public SecurityContext fromConnection(ConnectionContext connection) {
        return SecurityContext.user(
                connection.getUserId(),
                connection.getTenantId(),
                resolveTeams(connection.getTenantId(), connection.getUserId()));
    }

    private List<String> resolveTeams(String tenantId, String username) {
        return teamService.byMember(tenantId, username).stream()
                .map(TeamDocument::getName)
                .toList();
    }
}
