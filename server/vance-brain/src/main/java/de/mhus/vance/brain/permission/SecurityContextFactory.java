package de.mhus.vance.brain.permission;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
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
 * connections cache the resolved {@link SecurityContext} on the
 * {@link ConnectionContext} for the connection lifetime — see
 * {@link #fromConnection} — so a chatty leitkanal session doesn't re-query team
 * membership per frame.
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
        List<String> teams = resolveTeams(tenantId, username);
        String confinedTo = credentialProjectConfinement(request);
        SecurityContext ctx = confinedTo == null
                ? SecurityContext.user(username, tenantId, teams)
                : SecurityContext.restrictedUser(username, tenantId, teams, confinedTo);
        request.setAttribute(REQ_ATTR_CONTEXT, ctx);
        return ctx;
    }

    /**
     * The project an attenuated credential confines this request to, or
     * {@code null} for a normal one.
     *
     * <p>Reading it here rather than at each call site is deliberate: this is
     * the one funnel every HTTP-borne {@link SecurityContext} passes through, so
     * a confinement cannot be forgotten by a controller — and a controller has
     * no way to opt out of it either.
     *
     * <p>{@code SCRIPT_RUN} is confined the same way. It was already
     * loopback-bound and registry-gated, so this is not what makes it safe, but
     * it does close the gap where a script token reached a project other than
     * the run's — the claim said which one and nobody downstream compared it.
     */
    private static @org.jspecify.annotations.Nullable String credentialProjectConfinement(
            HttpServletRequest request) {
        if (!(request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims)) {
            return null;
        }
        boolean confined = claims.tokenType() == TokenType.INTEGRATION
                || claims.tokenType() == TokenType.SCRIPT_RUN;
        if (!confined) {
            return null;
        }
        String projectId = claims.projectId();
        return projectId == null || projectId.isBlank() ? null : projectId;
    }

    public SecurityContext fromConnection(ConnectionContext connection) {
        SecurityContext cached = connection.getSecurityContext();
        if (cached != null) {
            return cached;
        }
        SecurityContext ctx = SecurityContext.user(
                connection.getUserId(),
                connection.getTenantId(),
                resolveTeams(connection.getTenantId(), connection.getUserId()));
        connection.cacheSecurityContext(ctx);
        return ctx;
    }

    /**
     * Build a {@link SecurityContext} for a tool invocation that needs an
     * <em>additional</em> per-target check beyond the scope check
     * {@code ToolDispatcher} already ran (e.g. a kit install or a cross-project
     * spawn into a project other than the caller's). A blank {@code userId}
     * (internal/system-originated work) yields {@link SecurityContext#SYSTEM}.
     * Not request-cached — cross-scope tool actions are rare; the hot
     * per-dispatch path caches teams in {@code ToolDispatcher} itself.
     */
    public SecurityContext forToolSubject(String tenantId,
            @org.jspecify.annotations.Nullable String userId) {
        if (userId == null || userId.isBlank()) {
            return SecurityContext.SYSTEM;
        }
        return SecurityContext.user(userId, tenantId, resolveTeams(tenantId, userId));
    }

    /**
     * The mandatory {@link de.mhus.vance.shared.permission.WriteActor} for a
     * tool-driven DocumentService write. Subject via {@link #forToolSubject}
     * (null userId → SYSTEM subject); the reason follows the agreed rule — a
     * deliberate write into {@code _vance/} is a system resource
     * ({@code WriteReason.SYSTEM}), everything else is the user's own write
     * ({@code WriteReason.USER}). (F1)
     */
    public de.mhus.vance.shared.permission.WriteActor writeActor(
            String tenantId,
            @org.jspecify.annotations.Nullable String userId,
            @org.jspecify.annotations.Nullable String path) {
        SecurityContext subject = forToolSubject(tenantId, userId);
        return path != null && path.startsWith("_vance/")
                ? de.mhus.vance.shared.permission.WriteActor.system(subject)
                : de.mhus.vance.shared.permission.WriteActor.user(subject);
    }

    private List<String> resolveTeams(String tenantId, String username) {
        return teamService.byMember(tenantId, username).stream()
                .map(TeamDocument::getName)
                .toList();
    }
}
