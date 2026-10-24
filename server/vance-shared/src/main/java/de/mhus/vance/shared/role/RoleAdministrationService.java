package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.Roles;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Changing roles on behalf of an actor — the one place the assignment rules
 * live, used by the admin REST surface, the approval of role requests and the
 * operator shell alike.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li><b>Authority.</b> Tenant roles need tenant ADMIN ({@code Tenant WRITE});
 *       project memberships need ADMIN on the project.</li>
 *   <li><b>Nobody above themselves.</b> No one assigns a tenant role stronger
 *       than their own, nor changes the role of someone who holds a stronger
 *       one.</li>
 *   <li><b>The last tenant admin stays.</b> Demoting the only TENANT_ADMIN of a
 *       tenant is refused — nobody could appoint a new one from inside. Only
 *       persons count: the operator's {@code _vance-admin} account is no one
 *       who could log in and appoint somebody.</li>
 *   <li><b>Tenant roles are for persons.</b> Service accounts ({@code _}
 *       prefix) hold {@link TenantRole#USER} only; teams hold project roles
 *       only.</li>
 *   <li><b>Only what exists.</b> A role names an existing user or team — a
 *       role stored for a name nobody holds yet would be inherited by whoever
 *       takes that name.</li>
 *   <li><b>External tenant roles stay external.</b> When the provider takes
 *       tenant roles from another system, changing them here is refused.</li>
 * </ul>
 *
 * <p>SYSTEM — the operator shell — holds every right, but the structural rules
 * (last tenant admin, persons only, existence) apply to it too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleAdministrationService {

    private final RoleAssignmentService assignments;
    private final PermissionService permissionService;
    private final UserService userService;
    private final TeamService teamService;
    private final ProjectService projectService;

    /** Stored tenant-role assignments (everyone else holds USER). Needs tenant ADMIN. */
    public List<RoleAssignmentDocument> listTenantRoles(SecurityContext actor, String tenantId) {
        permissionService.enforce(actor, new Resource.Tenant(tenantId), Action.WRITE);
        return assignments.forScope(tenantId, RoleScopeType.TENANT, tenantId);
    }

    /** Members of a project. Needs ADMIN on the project. */
    public List<RoleAssignmentDocument> listProjectMembers(SecurityContext actor, String tenantId, String project) {
        permissionService.enforce(actor, new Resource.Project(tenantId, project), Action.ADMIN);
        return assignments.forScope(tenantId, RoleScopeType.PROJECT, project);
    }

    /**
     * Sets the tenant role of {@code username}; {@link TenantRole#USER} removes
     * the stored role.
     */
    public void assignTenantRole(SecurityContext actor, String tenantId, String username, TenantRole role) {
        permissionService.enforce(actor, new Resource.Tenant(tenantId), Action.WRITE);
        if (!permissionService.storesTenantRoles()) {
            throw refused("tenant roles are managed by the external permission provider, not in Vance");
        }
        if (userService.findByTenantAndName(tenantId, username).isEmpty()) {
            throw new RoleAssignmentException(
                    RoleAssignmentException.Kind.NOT_FOUND, "no user '" + username + "' in tenant '" + tenantId + "'");
        }
        if (role != TenantRole.USER && username.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)) {
            throw refused("service account '" + username + "' can only hold the tenant role USER");
        }
        TenantRole actorRole = permissionService.tenantRoleOf(actor);
        TenantRole current = assignments.tenantRoleOf(tenantId, username);
        if (!Roles.atLeast(actorRole, role)) {
            throw refused("cannot assign " + role + " — it is above your own role " + actorRole);
        }
        if (!Roles.atLeast(actorRole, current)) {
            throw refused("cannot change the role of '" + username + "' — they hold " + current
                    + ", above your own role " + actorRole);
        }
        if (current == TenantRole.TENANT_ADMIN
                && role != TenantRole.TENANT_ADMIN
                && !username.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)
                && personsWithTenantAdmin(tenantId) <= 1) {
            throw refused("'" + username + "' is the last TENANT_ADMIN of tenant '" + tenantId
                    + "' — appoint another one first");
        }
        assignments.setTenantRole(tenantId, username, role, actor.subjectId());
        log.info("tenant role of '{}' in '{}' set to {} by '{}'", username, tenantId, role, actor.subjectId());
    }

    /** Makes a user or team a member of {@code project} with {@code role}. */
    public RoleAssignmentDocument assignProjectRole(
            SecurityContext actor,
            String tenantId,
            String project,
            RoleSubjectType subjectType,
            String subjectId,
            ProjectRole role) {
        permissionService.enforce(actor, new Resource.Project(tenantId, project), Action.ADMIN);
        requireMemberProject(tenantId, project);
        requireSubject(tenantId, subjectType, subjectId);
        RoleAssignmentDocument saved =
                assignments.setProjectRole(tenantId, project, subjectType, subjectId, role, actor.subjectId());
        log.info(
                "project role of {}:{} in '{}/{}' set to {} by '{}'",
                subjectType,
                subjectId,
                tenantId,
                project,
                role,
                actor.subjectId());
        return saved;
    }

    /** Removes the membership of a user or team. True if there was one. */
    public boolean removeProjectRole(
            SecurityContext actor, String tenantId, String project, RoleSubjectType subjectType, String subjectId) {
        permissionService.enforce(actor, new Resource.Project(tenantId, project), Action.ADMIN);
        return assignments.remove(tenantId, RoleScopeType.PROJECT, project, subjectType, subjectId);
    }

    private long personsWithTenantAdmin(String tenantId) {
        return assignments.usersWithTenantRole(tenantId, TenantRole.TENANT_ADMIN).stream()
                .filter(user -> !user.startsWith(UserService.SERVICE_ACCOUNT_PREFIX))
                .count();
    }

    private void requireMemberProject(String tenantId, String project) {
        if (projectService.findByTenantAndName(tenantId, project).isEmpty()) {
            throw new RoleAssignmentException(
                    RoleAssignmentException.Kind.NOT_FOUND,
                    "no project '" + project + "' in tenant '" + tenantId + "'");
        }
        // Who works in a system project is structure, not membership: _tenant
        // is TENANT_ADMIN's, a hub its owner's.
        if (project.startsWith(ProjectService.SYSTEM_NAME_PREFIX)) {
            throw refused("system project '" + project + "' has no members");
        }
    }

    private void requireSubject(String tenantId, RoleSubjectType subjectType, String subjectId) {
        boolean exists = subjectType == RoleSubjectType.USER
                ? userService.existsByTenantAndName(tenantId, subjectId)
                : teamService.existsByTenantAndName(tenantId, subjectId);
        if (!exists) {
            throw new RoleAssignmentException(
                    RoleAssignmentException.Kind.NOT_FOUND,
                    "no " + subjectType.name().toLowerCase(Locale.ROOT) + " '" + subjectId + "' in tenant '" + tenantId
                            + "'");
        }
    }

    private static RoleAssignmentException refused(String message) {
        return new RoleAssignmentException(RoleAssignmentException.Kind.REFUSED, message);
    }
}
