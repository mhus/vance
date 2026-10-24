package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.project.ProjectService;
import org.jspecify.annotations.Nullable;

/**
 * What each role permits — stage 4 of {@link PermissionService#check}. Pure
 * function of the subject's roles; it neither looks anything up nor knows where
 * the roles came from.
 *
 * <p>The table (see {@code specification/public/permission-system.md}):
 *
 * <pre>
 * Resource                READ                 WRITE/CREATE/DELETE/START/EXECUTE   ADMIN/IMPERSONATE
 * Tenant                  USER                 CREATE: CREATOR · WRITE/DELETE:      TENANT_ADMIN
 *                                              ADMIN · START/EXECUTE: USER
 * Project/Session/        P-READER, T-ADMIN    P-MEMBER, T-ADMIN                    P-ADMIN, T-ADMIN
 *   ThinkProcess/Document
 *   (_tenant: READ for every member)
 * Setting project         as its project       as its project                       as its project
 * Setting user            own, T-ADMIN         own, T-ADMIN                         own, T-ADMIN
 * Setting tenant          USER                 TENANT_ADMIN                         TENANT_ADMIN
 * User / Team             USER                 T-ADMIN                              T-ADMIN
 * InboxItem               assignee or a teammate of the assignee (R5), any action
 * </pre>
 *
 * <p>The structural restrictions on top of this (writing {@code _tenant},
 * {@code _vance/}, foreign hubs, identity crossings) are not in the table —
 * they are {@link CorePreconditions}, which can only deny.
 */
public final class RoleTable {

    private RoleTable() {}

    /**
     * @param tenantRole  the subject's tenant role
     * @param projectRole the subject's role in the project the resource belongs
     *                    to ({@code null}: not a member, or the resource has no
     *                    project)
     */
    public static boolean allows(
            SecurityContext subject,
            Resource resource,
            Action action,
            TenantRole tenantRole,
            @Nullable ProjectRole projectRole,
            TeamMembership teams) {
        return switch (resource) {
            case Resource.Tenant ignored -> tenantAllows(tenantRole, action);
            case Resource.Project p -> projectAllows(p.projectName(), action, tenantRole, projectRole);
            case Resource.Session s -> projectAllows(s.projectName(), action, tenantRole, projectRole);
            case Resource.ThinkProcess tp -> projectAllows(tp.projectName(), action, tenantRole, projectRole);
            case Resource.Document d -> projectAllows(d.projectName(), action, tenantRole, projectRole);
            case Resource.Setting st -> settingAllows(subject, st, action, tenantRole, projectRole);
            case Resource.User ignored -> action == Action.READ || Roles.atLeast(tenantRole, TenantRole.ADMIN);
            case Resource.Team ignored -> action == Action.READ || Roles.atLeast(tenantRole, TenantRole.ADMIN);
            case Resource.InboxItem i -> inboxAllows(subject, i, teams);
        };
    }

    /** The project role an action on a project-bound resource needs. */
    public static ProjectRole requiredProjectRole(Action action) {
        return switch (action) {
            case READ -> ProjectRole.READER;
            case WRITE, CREATE, DELETE, START, EXECUTE -> ProjectRole.MEMBER;
            case ADMIN, IMPERSONATE -> ProjectRole.ADMIN;
        };
    }

    private static boolean tenantAllows(TenantRole tenantRole, Action action) {
        return switch (action) {
            // Every member reads the tenant and starts work in it; what they
            // may then touch is decided per project.
            case READ, START, EXECUTE -> true;
            // Creating inside the tenant = creating a project.
            case CREATE -> Roles.atLeast(tenantRole, TenantRole.CREATOR);
            // Administering the tenant's members and projects — not its
            // configuration.
            case WRITE, DELETE -> Roles.atLeast(tenantRole, TenantRole.ADMIN);
            // The tenant configuration itself.
            case ADMIN, IMPERSONATE -> Roles.atLeast(tenantRole, TenantRole.TENANT_ADMIN);
        };
    }

    private static boolean projectAllows(
            String project, Action action, TenantRole tenantRole, @Nullable ProjectRole projectRole) {
        // _tenant holds the cascade defaults every member resolves through
        // (settings, recipes, models) — readable by all of them. Writing it is
        // narrowed to TENANT_ADMIN by the preconditions.
        if (ProjectService.TENANT_PROJECT_NAME.equals(project) && action == Action.READ) {
            return true;
        }
        if (Roles.atLeast(tenantRole, TenantRole.ADMIN)) {
            return true;
        }
        return Roles.atLeast(projectRole, requiredProjectRole(action));
    }

    private static boolean settingAllows(
            SecurityContext subject,
            Resource.Setting setting,
            Action action,
            TenantRole tenantRole,
            @Nullable ProjectRole projectRole) {
        return switch (setting.referenceType()) {
            case "project" -> projectAllows(setting.referenceId(), action, tenantRole, projectRole);
            case "user" ->
                setting.referenceId().equals(subject.subjectId()) || Roles.atLeast(tenantRole, TenantRole.ADMIN);
            // "tenant" and anything unknown → the tenant scope, the strictest
            // reading of an unrecognised reference.
            default -> action == Action.READ || Roles.atLeast(tenantRole, TenantRole.TENANT_ADMIN);
        };
    }

    /**
     * R5: an inbox item is reachable by its assignee, or by someone sharing a
     * team with the assignee. An item without an assignee is reachable by
     * nobody. The same rule exists in {@code InboxAuthz#mayDecide}
     * (vance-brain) for the REST surface — change both or neither.
     */
    private static boolean inboxAllows(SecurityContext subject, Resource.InboxItem item, TeamMembership teams) {
        String assignee = item.assignedToUserId();
        if (assignee == null || assignee.isBlank()) {
            return false;
        }
        if (assignee.equals(subject.subjectId())) {
            return true;
        }
        if (subject.teams().isEmpty()) {
            return false;
        }
        for (String team : teams.teamNamesOf(item.tenantId(), assignee)) {
            if (subject.teams().contains(team)) {
                return true;
            }
        }
        return false;
    }
}
