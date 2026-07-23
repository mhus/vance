package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;

/** Shared parsing + ADMIN-scope enforcement for the {@code permission_grant_*} tools. */
final class GrantToolSupport {

    private GrantToolSupport() {}

    /** Grant management requires ADMIN on the target scope (tenant or project). */
    static void enforceScopeAdmin(PermissionService permissionService, SecurityContextFactory contextFactory,
            ToolInvocationContext ctx, GrantScopeType scopeType, String scopeId) {
        Resource scope = scopeType == GrantScopeType.TENANT
                ? new Resource.Tenant(ctx.tenantId())
                : new Resource.Project(ctx.tenantId(), scopeId);
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()), scope, Action.ADMIN);
    }

    /** Effective scopeId — TENANT grants are keyed on the tenant itself. */
    static String scopeId(GrantScopeType scopeType, ToolInvocationContext ctx, Map<String, Object> params) {
        if (scopeType == GrantScopeType.TENANT) {
            return ctx.tenantId();
        }
        String p = str(params, "scopeId");
        if (p == null || p.isBlank()) {
            p = ctx.projectId();
        }
        if (p == null || p.isBlank()) {
            throw new ToolException("'scopeId' (project) is required for a PROJECT-scope grant");
        }
        return p;
    }

    static GrantScopeType scopeType(Map<String, Object> params) {
        return parseEnum(GrantScopeType.class, req(params, "scopeType"), "scopeType");
    }

    static GrantSubjectType subjectType(Map<String, Object> params) {
        return parseEnum(GrantSubjectType.class, req(params, "subjectType"), "subjectType");
    }

    static GrantRole role(Map<String, Object> params) {
        return parseEnum(GrantRole.class, req(params, "role"), "role");
    }

    static String req(Map<String, Object> params, String key) {
        String v = str(params, key);
        if (v == null || v.isBlank()) {
            throw new ToolException("missing required parameter: " + key);
        }
        return v;
    }

    static @org.jspecify.annotations.Nullable String str(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid " + field + ": '" + value + "'");
        }
    }
}
