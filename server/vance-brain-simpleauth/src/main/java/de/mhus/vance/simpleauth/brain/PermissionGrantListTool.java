package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lists the grants on a scope. Requires ADMIN on that scope. */
@Component
@RequiredArgsConstructor
public class PermissionGrantListTool implements Tool {

    private final PermissionGrantService grants;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "permission_grant_list";
    }

    @Override
    public String description() {
        return "List the role grants on a tenant- or project-scope. Requires ADMIN on that scope.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scopeType", Map.of("type", "string", "enum", List.of("TENANT", "PROJECT"),
                "description", "TENANT or PROJECT."));
        props.put("scopeId", Map.of("type", "string",
                "description", "Project name for PROJECT scope (defaults to the current project)."));
        return Map.of("type", "object", "properties", props, "required", List.of("scopeType"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        GrantScopeType scopeType = GrantToolSupport.scopeType(params);
        String scopeId = GrantToolSupport.scopeId(scopeType, ctx, params);

        GrantToolSupport.enforceScopeAdmin(permissionService, contextFactory, ctx, scopeType, scopeId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PermissionGrantDocument g : grants.forScope(ctx.tenantId(), scopeType, scopeId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subjectType", g.getSubjectType().name());
            row.put("subjectId", g.getSubjectId());
            row.put("role", g.getRole().name());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scopeType", scopeType.name());
        out.put("scopeId", scopeId);
        out.put("grants", rows);
        return out;
    }
}
