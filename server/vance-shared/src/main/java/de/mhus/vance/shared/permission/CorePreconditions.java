package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.project.ProjectService;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Structural rules — stage 3 of {@link PermissionService#check}. They describe
 * how Vance is built (who writes the tenant configuration, whose a hub is),
 * not company policy, so they hold for every provider and run before the
 * provider's roles are applied. They can only deny: passing them permits
 * nothing by itself.
 *
 * <ul>
 *   <li><b>Tenant guard</b> — a user never acts across tenants.</li>
 *   <li><b>{@code _tenant}</b> — anything but reading needs TENANT_ADMIN.</li>
 *   <li><b>Other {@code _}-projects</b> — foreign hubs {@code _user_<other>} and
 *       any further system project need tenant ADMIN. The own hub is open to
 *       its owner.</li>
 *   <li><b>{@code _vance/}</b> — writing the configuration namespace of a
 *       project needs project ADMIN (or tenant ADMIN). It holds recipes, hooks,
 *       schedulers, events — everything that shapes how the project
 *       behaves.</li>
 *   <li><b>No reach above the own role</b> — acting on a user (other than
 *       reading) or on their user-scoped settings needs a tenant role at least
 *       as strong as theirs. Without this an ADMIN would reach TENANT_ADMIN by
 *       resetting a tenant admin's password instead of granting the role.</li>
 * </ul>
 */
final class CorePreconditions {

    private static final String CONFIG_PREFIX = "_vance/";

    private CorePreconditions() {}

    /**
     * @param tenantRole  the subject's tenant role
     * @param projectRole the subject's role in the resource's project
     * @param targetRole  tenant role of another user, looked up only when the
     *                    resource acts on one
     */
    static boolean permit(
            SecurityContext subject,
            Resource resource,
            Action action,
            TenantRole tenantRole,
            @Nullable ProjectRole projectRole,
            Function<String, TenantRole> targetRole) {
        if (!subject.tenantId().equals(tenantOf(resource))) {
            return false;
        }
        String project = projectOf(resource);
        if (project != null && !projectPermits(subject, project, action, tenantRole)) {
            return false;
        }
        if (resource instanceof Resource.Document d
                && isWrite(action)
                && d.path().startsWith(CONFIG_PREFIX)
                && !ProjectService.TENANT_PROJECT_NAME.equals(d.projectName())
                && !Roles.atLeast(projectRole, ProjectRole.ADMIN)
                && !Roles.atLeast(tenantRole, TenantRole.ADMIN)) {
            return false;
        }
        String otherUser = otherUserActedOn(subject, resource, action);
        return otherUser == null || Roles.atLeast(tenantRole, targetRole.apply(otherUser));
    }

    private static boolean projectPermits(
            SecurityContext subject, String project, Action action, TenantRole tenantRole) {
        if (ProjectService.TENANT_PROJECT_NAME.equals(project)) {
            return action == Action.READ || tenantRole == TenantRole.TENANT_ADMIN;
        }
        if (!project.startsWith(ProjectService.SYSTEM_NAME_PREFIX) || isOwnHub(subject, project)) {
            return true;
        }
        return Roles.atLeast(tenantRole, TenantRole.ADMIN);
    }

    /** Whether {@code project} is the personal hub of {@code subject}. */
    static boolean isOwnHub(SecurityContext subject, String project) {
        return project.equals(ProjectService.HUB_PROJECT_NAME_PREFIX + subject.subjectId());
    }

    /**
     * The user a non-read action reaches into, other than the subject itself:
     * the user resource, or the owner of user-scoped settings.
     */
    private static @Nullable String otherUserActedOn(SecurityContext subject, Resource resource, Action action) {
        if (action == Action.READ) {
            return null;
        }
        String user =
                switch (resource) {
                    case Resource.User u -> u.username();
                    case Resource.Setting st when "user".equals(st.referenceType()) -> st.referenceId();
                    default -> null;
                };
        return user == null || user.equals(subject.subjectId()) ? null : user;
    }

    private static boolean isWrite(Action action) {
        return switch (action) {
            case WRITE, CREATE, DELETE, IMPERSONATE -> true;
            default -> false;
        };
    }

    /** The project a resource lives in, or {@code null} for tenant-level resources. */
    static @Nullable String projectOf(Resource resource) {
        return switch (resource) {
            case Resource.Project p -> p.projectName();
            case Resource.Session s -> s.projectName();
            case Resource.ThinkProcess tp -> tp.projectName();
            case Resource.Document d -> d.projectName();
            case Resource.Setting st when "project".equals(st.referenceType()) -> st.referenceId();
            default -> null;
        };
    }

    static String tenantOf(Resource resource) {
        return switch (resource) {
            case Resource.Tenant t -> t.tenantId();
            case Resource.Project p -> p.tenantId();
            case Resource.Document d -> d.tenantId();
            case Resource.Setting s -> s.tenantId();
            case Resource.Session s -> s.tenantId();
            case Resource.ThinkProcess tp -> tp.tenantId();
            case Resource.Team tm -> tm.tenantId();
            case Resource.User u -> u.tenantId();
            case Resource.InboxItem i -> i.tenantId();
        };
    }
}
