package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
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

/** Grants (or updates) a role for a subject on a scope. Requires ADMIN on that scope. */
@Component
@RequiredArgsConstructor
public class PermissionGrantSetTool implements Tool {

    private final PermissionGrantService grants;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "permission_grant_set";
    }

    @Override
    public String description() {
        return "Grant or update a role (READER/WRITER/ADMIN) for a user or team on a "
                + "tenant- or project-scope. Requires ADMIN on the target scope.";
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
                "description", "Project name for PROJECT scope (defaults to the current project). Ignored for TENANT."));
        props.put("subjectType", Map.of("type", "string", "enum", List.of("USER", "TEAM"),
                "description", "USER or TEAM."));
        props.put("subjectId", Map.of("type", "string", "description", "Username or team name."));
        props.put("role", Map.of("type", "string", "enum", List.of("READER", "WRITER", "ADMIN"),
                "description", "The role to grant."));
        return Map.of("type", "object", "properties", props,
                "required", List.of("scopeType", "subjectType", "subjectId", "role"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        GrantScopeType scopeType = GrantToolSupport.scopeType(params);
        String scopeId = GrantToolSupport.scopeId(scopeType, ctx, params);
        GrantSubjectType subjectType = GrantToolSupport.subjectType(params);
        String subjectId = GrantToolSupport.req(params, "subjectId");
        GrantRole role = GrantToolSupport.role(params);

        GrantToolSupport.enforceScopeAdmin(permissionService, contextFactory, ctx, scopeType, scopeId);

        PermissionGrantDocument saved = grants.set(ctx.tenantId(), scopeType, scopeId,
                subjectType, subjectId, role, ctx.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granted", true);
        out.put("scopeType", scopeType.name());
        out.put("scopeId", scopeId);
        out.put("subjectType", subjectType.name());
        out.put("subjectId", subjectId);
        out.put("role", saved.getRole().name());
        return out;
    }
}
