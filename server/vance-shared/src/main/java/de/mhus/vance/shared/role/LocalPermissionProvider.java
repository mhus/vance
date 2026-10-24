package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.permission.PermissionProvider;
import de.mhus.vance.shared.permission.Roles;
import de.mhus.vance.shared.permission.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * The core's role source: tenant roles and project memberships as stored in
 * {@code role_assignments}. Active unless an addon contributes another
 * {@link PermissionProvider} (see {@link LocalPermissionProviderConfiguration}).
 *
 * <p>No veto: the local provider adds no prohibitions beyond the role table.
 */
public class LocalPermissionProvider implements PermissionProvider {

    private final RoleAssignmentService assignments;

    public LocalPermissionProvider(RoleAssignmentService assignments) {
        this.assignments = assignments;
    }

    @Override
    public TenantRole tenantRole(String tenantId, String username) {
        return assignments.tenantRoleOf(tenantId, username);
    }

    /** The strongest of the subject's own membership and those of its teams. */
    @Override
    public @Nullable ProjectRole projectRole(SecurityContext subject, String tenantId, String project) {
        ProjectRole best = null;
        for (RoleAssignmentDocument a : assignments.forScope(tenantId, RoleScopeType.PROJECT, project)) {
            boolean matches = a.getSubjectType() == RoleSubjectType.USER
                    ? a.getSubjectId().equals(subject.subjectId())
                    : subject.teams().contains(a.getSubjectId());
            if (matches) {
                best = Roles.max(best, a.getProjectRole());
            }
        }
        return best;
    }

    @Override
    public boolean storesTenantRoles() {
        return true;
    }
}
