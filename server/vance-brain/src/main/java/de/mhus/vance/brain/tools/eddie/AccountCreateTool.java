package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.roles.RoleChangeSupport;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaKind;
import de.mhus.vance.shared.user.UserQuotaService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Creates a person's account — the same act as the admin user list's
 * "create", gated the same way: tenant ADMIN, and the tenant's seat quota.
 *
 * <p>Without a password, on purpose. A password passed through a tool lands
 * in the chat history, the session export and the provider's logs. The
 * account is created ready for an administrator to set a password in the
 * settings, or to prepare it for SSO.
 *
 * <p>Service accounts ({@code _} prefix) are not created here — they are
 * token-only identities minted from the operator shell.
 *
 * <p>Roles may be given right away. They go through {@link RoleChangeSupport}
 * after the account exists — applied where the caller's authority reaches,
 * requested where it does not — and each one reports its own outcome: a
 * refused role does not undo the account.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountCreateTool implements Tool {

    /** Same rule as {@code UserCreateRequest.name}. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]*$");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Username: lower-case letters, digits, '.', '-', '_'; "
                                                    + "not starting with '_'."),
                            "title", Map.of("type", "string", "description", "Display name, e.g. 'Ada Lovelace'."),
                            "email", Map.of("type", "string", "description", "Email address (optional)."),
                            "tenantRole",
                                    Map.of(
                                            "type",
                                            "string",
                                            "enum",
                                            List.of("USER", "CREATOR", "ADMIN", "TENANT_ADMIN"),
                                            "description",
                                            "Tenant role (default USER). CREATOR may create projects."),
                            "projectRoles",
                                    Map.of(
                                            "type",
                                            "array",
                                            "description",
                                            "Project memberships to give right away.",
                                            "items",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of(
                                                            "project",
                                                            Map.of("type", "string", "description", "Project name."),
                                                            "role",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "enum",
                                                                    List.of("READER", "MEMBER", "ADMIN"))),
                                                    "required",
                                                    List.of("project", "role")))),
            "required", List.of("name"));

    private final UserService userService;
    private final UserQuotaService quotaService;
    private final AdminToolSupport admin;
    private final RoleChangeSupport roleChanges;

    @Override
    public String name() {
        return "account_create";
    }

    @Override
    public String description() {
        return "Create a user account for a person, optionally with a tenant role and project memberships. "
                + "Needs tenant ADMIN and a free seat. No password is set — tell the user an administrator "
                + "sets one in the settings (or prepares SSO). Roles beyond your own authority become "
                + "requests for approval; the result lists each role's outcome.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "add a new user, create an account, invite a colleague";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, ToolLabels.WRITE);
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext actor = admin.actor(ctx, name());
        String name = AdminToolSupport.req(params, "name");
        if (name.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)) {
            throw new ToolException("'" + name + "' would be a service account — those are created in the "
                    + "operator shell, not here");
        }
        if (!NAME.matcher(name).matches()) {
            throw new ToolException("invalid username '" + name + "' — lower-case letters, digits, '.', '-' or '_'");
        }
        // Parse every role before anything is written: a typo must not leave
        // an account behind with half its roles.
        TenantRole tenantRole = tenantRole(AdminToolSupport.str(params, "tenantRole"));
        List<ProjectGrant> projectGrants = projectGrants(params);

        admin.enforce(actor, new Resource.Tenant(ctx.tenantId()), Action.WRITE);
        try {
            quotaService.checkOneMore(ctx.tenantId(), UserQuotaKind.SEATS);
        } catch (UserQuotaService.UserQuotaExceededException e) {
            throw new ToolException(e.getMessage(), e);
        }
        UserDocument created;
        try {
            created = userService.create(
                    ctx.tenantId(),
                    name,
                    null,
                    AdminToolSupport.str(params, "title"),
                    AdminToolSupport.str(params, "email"));
        } catch (UserService.UserAlreadyExistsException | UserService.ReservedNameException e) {
            throw new ToolException(e.getMessage(), e);
        }
        log.info("account_create: tenant='{}' user='{}' by='{}'", ctx.tenantId(), name, ctx.userId());
        admin.record(
                ctx,
                EddieActivityKind.ACCOUNT_CREATED,
                "Created account `" + name + "`",
                List.of(EntityRef.user(name)));

        List<Map<String, Object>> roles = new ArrayList<>();
        if (tenantRole != null && tenantRole != TenantRole.USER) {
            roles.add(applyRole(ctx, RoleRequestService.Target.tenantGrant(ctx.tenantId(), name, tenantRole)));
        }
        for (ProjectGrant g : projectGrants) {
            roles.add(applyRole(
                    ctx, RoleRequestService.Target.projectGrant(g.project(), RoleSubjectType.USER, name, g.role())));
        }

        Map<String, Object> out = AccountRows.summary(created, admin.tenantRoleOf(ctx.tenantId(), name));
        if (!roles.isEmpty()) {
            out.put("roles", roles);
        }
        out.put(
                "signInNote",
                "No password is set. An administrator sets one in the settings (users), or prepares the "
                        + "account for SSO — until then the account cannot sign in.");
        return out;
    }

    /** One role's outcome; a refusal is reported in place instead of failing the whole call. */
    private Map<String, Object> applyRole(ToolInvocationContext ctx, RoleRequestService.Target target) {
        try {
            return roleChanges.grant(ctx, name(), target, "Requested while creating the account.");
        } catch (ToolException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applied", false);
            out.put("requested", false);
            out.put("scopeType", target.scopeType().name());
            out.put("scopeId", target.scopeId());
            out.put("error", e.getMessage());
            return out;
        }
    }

    private static @Nullable TenantRole tenantRole(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TenantRole.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid tenantRole '" + raw + "'", e);
        }
    }

    private static List<ProjectGrant> projectGrants(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("projectRoles");
        List<ProjectGrant> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("'projectRoles' must be a list of {project, role}");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new ToolException("'projectRoles' entries must be objects {project, role}");
            }
            Object project = m.get("project");
            Object role = m.get("role");
            if (project == null || project.toString().isBlank() || role == null) {
                throw new ToolException("each 'projectRoles' entry needs 'project' and 'role'");
            }
            try {
                out.add(new ProjectGrant(
                        project.toString().trim(),
                        ProjectRole.valueOf(role.toString().trim().toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                throw new ToolException("invalid project role '" + role + "' — READER, MEMBER or ADMIN", e);
            }
        }
        return out;
    }

    private record ProjectGrant(String project, ProjectRole role) {}
}
