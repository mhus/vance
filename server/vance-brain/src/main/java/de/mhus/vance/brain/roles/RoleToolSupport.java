package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
