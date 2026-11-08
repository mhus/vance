package de.mhus.vance.brain.permission;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
            throw new IllegalStateException("No authenticated user on request — BrainAccessFilter must run first");
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
     *
     * <p>{@code OAUTH} tokens of the project door carry the same pin and get
     * the same confinement; unpinned OAuth tokens (global door, agents door,
     * account scope) come out unconfined, like their integration-token
     * counterparts.
     */
    public static @org.jspecify.annotations.Nullable String credentialProjectConfinement(HttpServletRequest request) {
        if (!(request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims)) {
            return null;
        }
        boolean confined = claims.tokenType() == TokenType.INTEGRATION
                || claims.tokenType() == TokenType.SCRIPT_RUN
                || claims.tokenType() == TokenType.OAUTH;
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
    public SecurityContext forToolSubject(String tenantId, @org.jspecify.annotations.Nullable String userId) {
        if (userId == null || userId.isBlank()) {
            return SecurityContext.SYSTEM;
        }
        return SecurityContext.user(userId, tenantId, resolveTeams(tenantId, userId));
    }

    /**
     * The {@link SecurityContext} a tool invocation acts under: the calling
     * user, and — when the call carries a credential confinement
     * ({@link ToolInvocationContext#confinedToProject()}) — confined to that
     * project, so a project-pinned token cannot reach further through a tool
     * than it could directly. Use this, not {@link #forToolSubject(String,
     * String)}, wherever a tool decides what its <em>caller</em> may do; the
     * two-argument form is for acting on behalf of someone else (an inbox
     * owner, a decider), where the caller's credential is not the subject.
     *
     * <p>A confinement without a user is refused: nothing to attenuate, and
     * SYSTEM must not come out of a confined call.
     */
    public SecurityContext forToolSubject(ToolInvocationContext ctx) {
        String confinedTo = ctx.confinedToProject();
        String userId = ctx.userId();
        if (confinedTo == null) {
            return forToolSubject(ctx.tenantId(), userId);
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("A credential-confined tool call needs a user");
        }
        return SecurityContext.restrictedUser(userId, ctx.tenantId(), resolveTeams(ctx.tenantId(), userId), confinedTo);
    }

    /** {@link #writeActor(String, String, String)} for a tool call — honours the call's credential confinement. */
    public de.mhus.vance.shared.permission.WriteActor writeActor(
            ToolInvocationContext ctx, @org.jspecify.annotations.Nullable String path) {
        return de.mhus.vance.shared.permission.WriteActor.user(forToolSubject(ctx));
    }

    /**
     * The mandatory {@link de.mhus.vance.shared.permission.WriteActor} for a
     * tool- or user-driven DocumentService write. Subject via
     * {@link #forToolSubject} (null userId → SYSTEM subject); the reason is
     * always {@code WriteReason.USER}, whatever the path. A caller-controlled
     * path must never buy system trust: a {@code _vance/} target is exactly
     * where the reserved-namespace rule (project ADMIN) has to be asked, and a
     * SYSTEM reason would skip it at the document chokepoint. Code-determined
     * plumbing and dedicated {@code _vance/} authoring tools with their own
     * ADMIN check build {@code WriteActor.system(subject)} explicitly
     * (permission-system.md §5, "Anti-Pattern"). The {@code path} parameter is
     * kept so every call site names the target it writes. (F1)
     */
    public de.mhus.vance.shared.permission.WriteActor writeActor(
            String tenantId,
            @org.jspecify.annotations.Nullable String userId,
            @org.jspecify.annotations.Nullable String path) {
        return de.mhus.vance.shared.permission.WriteActor.user(forToolSubject(tenantId, userId));
    }

    /**
     * Build a {@link SecurityContext} from a verified
     * {@link TokenType#DESIGN_PREVIEW} token — the designer app's sandboxed
     * content route authenticates its path-segment token itself and still
     * runs the per-request READ check, so a permission change takes effect on
     * the next fetch, not when the token expires.
     *
     * <p>The token's {@code pid} claim becomes the context's project
     * confinement — the same attenuation an integration credential gets —
     * so the check can only ever come out <em>more</em> restrictive than the
     * minting user's own rights.
     */
    public SecurityContext fromDesignPreviewClaims(VanceJwtClaims claims) {
        if (claims.tokenType() != TokenType.DESIGN_PREVIEW) {
            throw new IllegalArgumentException("Not a design-preview token: " + claims.tokenType());
        }
        return SecurityContext.restrictedUser(
                claims.username(),
                claims.tenantId(),
                resolveTeams(claims.tenantId(), claims.username()),
                claims.projectId());
    }

    private List<String> resolveTeams(String tenantId, String username) {
        return teamService.byMember(tenantId, username).stream()
                .map(TeamDocument::getName)
                .toList();
    }
}
