package de.mhus.vance.shared.permission;

import java.util.List;

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
 * <p>For {@link SubjectType#SYSTEM}, use the {@link #SYSTEM} singleton.
 * {@link AllowAllPermissionResolver} permits it; future resolvers must too.
 */
public record SecurityContext(
        SubjectType subjectType,
        String subjectId,
        String tenantId,
        List<String> teams) {

    public SecurityContext {
        teams = List.copyOf(teams);
    }

    /** The pseudo-context for internal callers. Always permitted. */
    public static final SecurityContext SYSTEM =
            new SecurityContext(SubjectType.SYSTEM, "system", "*", List.of());

    /** Convenience for the typical case: an authenticated user. */
    public static SecurityContext user(String username, String tenantId, List<String> teams) {
        return new SecurityContext(SubjectType.USER, username, tenantId, teams);
    }
}
