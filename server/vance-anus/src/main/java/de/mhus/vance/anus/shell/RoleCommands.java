package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Operator view of the role model: tenant roles and project memberships.
 *
 * <p>Runs as the operator (SYSTEM): no authority check, the tenant is always an
 * explicit argument. The structural assignment rules still apply — the last
 * TENANT_ADMIN of a tenant cannot be demoted, tenant roles are for persons
 * (users, not teams, not {@code _} service accounts), and only existing users,
 * teams and projects get a role. Without {@code --project} a command works on
 * the tenant role; with it, on the membership in that project.
 *
 * <p>When the permission provider takes tenant roles from an external system,
 * {@code role set} on a tenant role is refused — the source is that system.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class RoleCommands {

    private static final List<String> HEADERS = List.of("SUBJECT-TYPE", "SUBJECT", "ROLE", "BY");

    private final RoleAdministrationService administration;
    private final PermissionService permissionService;

    @Command(
            name = {"role", "list"},
            description = "List the stored tenant roles (everyone else is USER), or the members of --project.")
    public String list(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p') @Nullable String project) {
        boolean ofProject = StringUtils.isNotBlank(project);
        List<RoleAssignmentDocument> rows = ofProject
                ? administration.listProjectMembers(SecurityContext.SYSTEM, tenant, project)
                : administration.listTenantRoles(SecurityContext.SYSTEM, tenant);
        String scope = ofProject ? "project '" + project + "'" : "tenant '" + tenant + "'";
        if (rows.isEmpty()) {
            return ofProject
                    ? "(no members in " + scope + ")"
                    : "(no tenant roles above USER stored in " + scope + ")" + externalNote();
        }
        return "Roles in " + scope + ":\n"
                + Tables.render(
                        HEADERS,
                        List.<Function<RoleAssignmentDocument, @Nullable Object>>of(
                                RoleAssignmentDocument::getSubjectType,
                                RoleAssignmentDocument::getSubjectId,
                                a -> a.getTenantRole() != null ? a.getTenantRole() : a.getProjectRole(),
                                RoleAssignmentDocument::getCreatedBy),
                        rows)
                + externalNote();
    }

    @Command(
            name = {"role", "show"},
            description = "Show the effective roles of a user — as the permission provider reports them.")
    public String show(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "user", shortName = 'u', required = true) String user,
            @Option(longName = "project", shortName = 'p') @Nullable String project) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tenant role of '")
                .append(user)
                .append("' in '")
                .append(tenant)
                .append("': ")
                .append(permissionService.tenantRoleOf(tenant, user));
        if (StringUtils.isNotBlank(project)) {
            ProjectRole projectRole =
                    permissionService.projectRoleOf(SecurityContext.user(user, tenant, List.of()), project);
            sb.append("\nOwn membership in project '")
                    .append(project)
                    .append("': ")
                    .append(projectRole == null ? "(none)" : projectRole)
                    .append(" (team memberships not included)");
        }
        return sb.toString();
    }

    @Command(
            name = {"role", "set"},
            description = "Set a tenant role (USER, CREATOR, ADMIN, TENANT_ADMIN) or, with --project, a project "
                    + "role (READER, MEMBER, ADMIN) for a user or team.")
    public String set(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p') @Nullable String project,
            @Option(longName = "subject-type", shortName = 't', defaultValue = "USER") String subjectType,
            @Option(longName = "subject-id", shortName = 'n', required = true) String subjectId,
            @Option(longName = "role", shortName = 'r', required = true) String role) {
        RoleSubjectType type = parse(RoleSubjectType.class, subjectType, "--subject-type");
        if (StringUtils.isBlank(project)) {
            if (type != RoleSubjectType.USER) {
                throw new IllegalArgumentException("tenant roles are held by users only — use --project for a team");
            }
            TenantRole tenantRole = parse(TenantRole.class, role, "--role");
            administration.assignTenantRole(SecurityContext.SYSTEM, tenant, subjectId, tenantRole);
            return "Tenant role of '" + subjectId + "' in '" + tenant + "' is now " + tenantRole + ".";
        }
        ProjectRole projectRole = parse(ProjectRole.class, role, "--role");
        administration.assignProjectRole(SecurityContext.SYSTEM, tenant, project, type, subjectId, projectRole);
        return type.name().toLowerCase(Locale.ROOT) + " '" + subjectId + "' is now " + projectRole + " of project '"
                + project + "'.";
    }

    @Command(
            name = {"role", "remove"},
            description = "Remove a project membership (--project), or set a tenant role back to USER.")
    public String remove(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p') @Nullable String project,
            @Option(longName = "subject-type", shortName = 't', defaultValue = "USER") String subjectType,
            @Option(longName = "subject-id", shortName = 'n', required = true) String subjectId) {
        RoleSubjectType type = parse(RoleSubjectType.class, subjectType, "--subject-type");
        if (StringUtils.isBlank(project)) {
            administration.assignTenantRole(SecurityContext.SYSTEM, tenant, subjectId, TenantRole.USER);
            return "Tenant role of '" + subjectId + "' in '" + tenant + "' is now USER.";
        }
        boolean removed = administration.removeProjectRole(SecurityContext.SYSTEM, tenant, project, type, subjectId);
        return removed
                ? "Removed " + type.name().toLowerCase(Locale.ROOT) + " '" + subjectId + "' from project '" + project
                        + "'."
                : "(" + type.name().toLowerCase(Locale.ROOT) + " '" + subjectId + "' is no member of project '"
                        + project + "')";
    }

    private String externalNote() {
        return permissionService.storesTenantRoles()
                ? ""
                : "\nNote: tenant roles come from the external permission provider — 'role show' reports them.";
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String option) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + option + ": '" + value + "'", e);
        }
    }
}
