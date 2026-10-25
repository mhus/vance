package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Parameter parsing shared by the {@code role_*} tools. */
final class RoleToolSupport {

    private RoleToolSupport() {}

    static RoleScopeType scopeType(Map<String, Object> params) {
        return parseEnum(RoleScopeType.class, req(params, "scopeType"), "scopeType");
    }

    /** Effective scope id — a TENANT scope is keyed on the tenant itself, PROJECT defaults to the current one. */
    static String scopeId(RoleScopeType scopeType, ToolInvocationContext ctx, Map<String, Object> params) {
        if (scopeType == RoleScopeType.TENANT) {
            return ctx.tenantId();
        }
        String p = str(params, "scopeId");
        if (p == null || p.isBlank()) {
            p = ctx.projectId();
        }
        if (p == null || p.isBlank()) {
            throw new ToolException("'scopeId' (project) is required for a PROJECT scope");
        }
        return p;
    }

    /**
     * The role change a grant names: a tenant role for a user, or a project
     * membership for a user or team.
     */
    static RoleRequestService.Target grantTarget(Map<String, Object> params, ToolInvocationContext ctx) {
        RoleScopeType scopeType = scopeType(params);
        String scopeId = scopeId(scopeType, ctx, params);
        RoleSubjectType subjectType = subjectType(params);
        String subjectId = req(params, "subjectId");
        if (scopeType == RoleScopeType.TENANT) {
            if (subjectType != RoleSubjectType.USER) {
                throw new ToolException("tenant roles are held by users only — use a PROJECT scope for a team");
            }
            return RoleRequestService.Target.tenantGrant(scopeId, subjectId, tenantRole(params));
        }
        return RoleRequestService.Target.projectGrant(scopeId, subjectType, subjectId, projectRole(params));
    }

    /** The role a revoke removes: a tenant role, or a project membership. */
    static RoleRequestService.Target revokeTarget(Map<String, Object> params, ToolInvocationContext ctx) {
        RoleScopeType scopeType = scopeType(params);
        return RoleRequestService.Target.revoke(
                scopeType, scopeId(scopeType, ctx, params), subjectType(params), req(params, "subjectId"));
    }

    /**
     * Parameter schema of the {@code role_*} change tools — the grant and
     * request tools take the same shape, so a model that knows one knows all.
     */
    static Map<String, Object> changeSchema(boolean withRole, String reasonDescription) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "scopeType",
                Map.of("type", "string", "enum", List.of("TENANT", "PROJECT"), "description", "TENANT or PROJECT."));
        props.put(
                "scopeId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Project name for PROJECT scope (defaults to the current project). Ignored for TENANT."));
        props.put(
                "subjectType",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("USER", "TEAM"),
                        "description",
                        "USER or TEAM. Teams hold project roles only."));
        props.put("subjectId", Map.of("type", "string", "description", "Username or team name."));
        List<String> required = new ArrayList<>(List.of("scopeType", "subjectType", "subjectId"));
        if (withRole) {
            props.put(
                    "role",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("READER", "MEMBER", "ADMIN", "USER", "CREATOR", "TENANT_ADMIN"),
                            "description",
                            "PROJECT scope: READER, MEMBER or ADMIN. TENANT scope: USER, CREATOR, ADMIN or "
                                    + "TENANT_ADMIN."));
            required.add("role");
        }
        props.put("reason", Map.of("type", "string", "description", reasonDescription));
        return Map.of("type", "object", "properties", props, "required", required);
    }

    static RoleSubjectType subjectType(Map<String, Object> params) {
        return parseEnum(RoleSubjectType.class, req(params, "subjectType"), "subjectType");
    }

    static TenantRole tenantRole(Map<String, Object> params) {
        return parseEnum(TenantRole.class, req(params, "role"), "role");
    }

    static ProjectRole projectRole(Map<String, Object> params) {
        return parseEnum(ProjectRole.class, req(params, "role"), "role");
    }

    static String req(Map<String, Object> params, String key) {
        String v = str(params, key);
        if (v == null || v.isBlank()) {
            throw new ToolException("missing required parameter: " + key);
        }
        return v;
    }

    static @Nullable String str(@Nullable Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid " + field + ": '" + value + "'", e);
        }
    }
}
