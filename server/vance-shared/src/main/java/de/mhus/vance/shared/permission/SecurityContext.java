package de.mhus.vance.shared.permission;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Identifies the principal making a request, plus the tenant and team
 * memberships needed to evaluate role-based rules without further lookups.
 *
 * <p>For {@link SubjectType#USER}, {@code subjectId} is {@code UserDocument.name}
 * (the username), {@code tenantId} is {@code TenantDocument.name}, and
 * {@code teams} contains {@code TeamDocument.name} values inside that tenant.
 * The teams list is resolved once at the inbound boundary (typically by the
 * controller / WS handler from the JWT claims) and frozen for the duration of
 * the request — it is not re-queried per check.
 *
 * <p>For {@link SubjectType#SYSTEM}, use the {@link #SYSTEM} singleton. Every
 * provider must permit it — it is the identity of internal, trusted callers.
 *
 * <p><b>{@link #restrictedToProject} is an attenuation, never a grant.</b> It
 * is set when the caller authenticated with a credential that is narrower than
 * the account behind it — today an
 * {@link de.mhus.vance.shared.jwt.TokenType#INTEGRATION} token pinned to one
 * project. {@link PermissionService} intersects it with whatever the resolver
 * says: the restriction can only ever take rights away. That direction is the
 * whole safety property, and it is enforced structurally — the field is read in
 * exactly one place, and no {@link PermissionResolver} sees it at all, so no
 * provider (including an enterprise one) can widen a restricted context by
 * accident.
 *
 * @param restrictedToProject project name this context is confined to, or
 *                            {@code null} for an unrestricted one (the normal
 *                            case: a password login, the Web-UI, the CLI).
 */
public record SecurityContext(
        SubjectType subjectType,
        String subjectId,
        String tenantId,
        List<String> teams,
        @Nullable String restrictedToProject) {

    public SecurityContext {
        teams = List.copyOf(teams);
    }

    /** The pseudo-context for internal callers. Always permitted. */
    public static final SecurityContext SYSTEM =
            new SecurityContext(SubjectType.SYSTEM, "system", "*", List.of(), null);

    /** Convenience for the typical case: an authenticated user. */
    public static SecurityContext user(String username, String tenantId, List<String> teams) {
        return new SecurityContext(SubjectType.USER, username, tenantId, teams, null);
    }

    /**
     * An authenticated user acting through a project-confined credential.
     * Everything outside {@code projectName} is denied regardless of what the
     * account itself may do.
     */
    public static SecurityContext restrictedUser(
            String username, String tenantId, List<String> teams, String projectName) {
        return new SecurityContext(SubjectType.USER, username, tenantId, teams, projectName);
    }
}
