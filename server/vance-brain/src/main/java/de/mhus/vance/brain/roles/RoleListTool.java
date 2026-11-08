package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists the stored roles on a scope: the tenant roles (tenant ADMIN only), or
 * the members of a project (project ADMIN).
 */
@Component
@RequiredArgsConstructor
public class RoleListTool implements Tool {

    private final RoleAdministrationService administration;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "role_list";
    }

    @Override
    public String description() {
        return "List stored roles: TENANT scope shows the tenant roles above USER (needs tenant ADMIN), "
                + "PROJECT scope shows the project's members and their roles (needs ADMIN on that project).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    /**
     * Reading roles is harmless, but it is an administrator's tool and has no
     * business hanging in an ordinary chat surface. The label puts it under the
     * {@code @executive} defer rule that Arthur and Eddie already apply;
     * {@link #deferred()} keeps it out of the default set of unrestricted
     * engines until a recipe asks for it explicitly.
     */
    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, "executive");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
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
                        "Project name for PROJECT scope (defaults to the current project)."));
        return Map.of("type", "object", "properties", props, "required", List.of("scopeType"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        RoleScopeType scopeType = RoleToolSupport.scopeType(params);
        String scopeId = RoleToolSupport.scopeId(scopeType, ctx, params);
        SecurityContext actor = contextFactory.forToolSubject(ctx);
        List<RoleAssignmentDocument> rows = scopeType == RoleScopeType.TENANT
                ? administration.listTenantRoles(actor, ctx.tenantId())
                : administration.listProjectMembers(actor, ctx.tenantId(), scopeId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (RoleAssignmentDocument a : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subjectType", a.getSubjectType().name());
            row.put("subjectId", a.getSubjectId());
            row.put("role", a.getTenantRole() != null ? a.getTenantRole().name() : String.valueOf(a.getProjectRole()));
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeType", scopeType.name());
        result.put("scopeId", scopeId);
        result.put("roles", out);
        if (scopeType == RoleScopeType.TENANT) {
            result.put("note", "Everyone not listed holds the tenant role USER.");
        }
        return result;
    }
}
