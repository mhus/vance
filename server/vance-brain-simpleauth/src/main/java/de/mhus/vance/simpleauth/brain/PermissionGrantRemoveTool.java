package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Removes a subject's grant on a scope. Requires ADMIN on that scope. */
@Component
@RequiredArgsConstructor
public class PermissionGrantRemoveTool implements Tool {

    private final PermissionGrantService grants;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "permission_grant_remove";
    }

    @Override
    public String description() {
        return "Remove a user's or team's grant on a tenant- or project-scope. "
                + "Requires ADMIN on that scope.";
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
        props.put("subjectType", Map.of("type", "string", "enum", List.of("USER", "TEAM"),
                "description", "USER or TEAM."));
        props.put("subjectId", Map.of("type", "string", "description", "Username or team name."));
        return Map.of("type", "object", "properties", props,
                "required", List.of("scopeType", "subjectType", "subjectId"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        GrantScopeType scopeType = GrantToolSupport.scopeType(params);
        String scopeId = GrantToolSupport.scopeId(scopeType, ctx, params);
        GrantSubjectType subjectType = GrantToolSupport.subjectType(params);
        String subjectId = GrantToolSupport.req(params, "subjectId");

        GrantToolSupport.enforceScopeAdmin(permissionService, contextFactory, ctx, scopeType, scopeId);

        boolean removed = grants.remove(ctx.tenantId(), scopeType, scopeId, subjectType, subjectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", removed);
        return out;
    }
}
