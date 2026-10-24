package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import org.jspecify.annotations.Nullable;

/**
 * Where roles come from. Vance owns the role model — the roles
 * ({@link TenantRole}, {@link ProjectRole}) and what each one permits
 * ({@link RoleTable}) are core. A provider only says <em>who holds which
 * role</em>, and may additionally forbid things.
 *
 * <p>Exactly one provider is active per instance; {@link PermissionService}
 * enforces that at startup. The core ships the local provider
 * ({@code LocalPermissionProvider}, roles stored in {@code role_assignments}),
 * which steps aside when an addon contributes another one — typically a
 * connection to a company governor system that maps its own roles onto the
 * Vance roles.
 *
 * <p><b>A provider can only narrow.</b> The role table is the upper bound:
 * whatever a provider reports, a role never permits more than the table says,
 * and {@link #vetoes} can only take rights away. The structural rules
 * (credential confinement, the SYSTEM trust boundary, the core preconditions)
 * run before the provider is consulted and are not visible to it.
 *
 * <p>Implementations must not throw for missing data — an unknown user simply
 * holds {@link TenantRole#USER} and no project role. A thrown exception is
 * treated as a denial by {@link PermissionService} (fail-closed). Lookups are
 * on the request hot path, so a provider caches; a revoked role may therefore
 * stay effective for the provider's cache TTL.
 */
public interface PermissionProvider {

    /**
     * Tenant role of {@code username}; never {@code null} — at least
     * {@link TenantRole#USER}. Tenant roles are held by users only, never by
     * teams.
     */
    TenantRole tenantRole(String tenantId, String username);

    /**
     * Project role of {@code subject} in {@code project} — its own membership
     * folded with the memberships of its teams ({@link SecurityContext#teams()}),
     * the strongest wins. {@code null} when it is no member. The tenant role is
     * <em>not</em> folded in here; {@link RoleTable} combines both.
     */
    @Nullable
    ProjectRole projectRole(SecurityContext subject, String tenantId, String project);

    /**
     * Additional prohibition on top of the role table — e.g. a governor that
     * locks certain documents or settings for certain roles. Asked only for
     * checks the core already allowed. Default: no veto.
     */
    default boolean vetoes(SecurityContext subject, Resource resource, Action action) {
        return false;
    }

    /**
     * Whether tenant roles are stored inside Vance and can therefore be changed
     * through Vance's role administration. {@code false} for a provider that
     * takes them from an external system: changing them is then that system's
     * business, and Vance refuses. Project memberships always live in Vance.
     */
    default boolean storesTenantRoles() {
        return false;
    }
}
