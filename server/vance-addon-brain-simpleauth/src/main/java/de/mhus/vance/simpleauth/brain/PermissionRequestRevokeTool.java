package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Asks for a subject's access to be removed. Replaces the former
 * {@code permission_grant_remove}.
 *
 * <p>Revoking is approval-gated for the same reason granting is. It looks
 * like the harmless direction, but an unwanted revoke is a clean denial of
 * service — in the worst case it removes the last administrator of a
 * scope, after which nobody can approve anything, including putting it
 * back.
 */
@Component
@RequiredArgsConstructor
public class PermissionRequestRevokeTool implements Tool {

    private final PermissionRequestSupport support;

    @Override
    public String name() {
        return "permission_request_revoke";
    }

    @Override
    public String description() {
        return "Request that a user's or team's role on a tenant- or project-scope be "
                + "removed. This does NOT change any permission: it creates a request and "
                + "asks an administrator to approve it. Report to the user that approval is "
                + "pending — do not claim access has been removed.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    /**
     * Same treatment as {@code permission_request_grant} — see the note
     * there. {@code primary()} alone does not keep a schema out of a
     * classified turn's manifest; {@code ContextToolsApi.classify} reads
     * {@link #deferred()}.
     */
    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scopeType", Map.of("type", "string", "enum", List.of("TENANT", "PROJECT"),
                "description", "TENANT or PROJECT."));
        props.put("scopeId", Map.of("type", "string",
                "description", "Project name for PROJECT scope (defaults to the current "
                        + "project). Ignored for TENANT."));
        props.put("subjectType", Map.of("type", "string", "enum", List.of("USER", "TEAM"),
                "description", "USER or TEAM."));
        props.put("subjectId", Map.of("type", "string", "description", "Username or team name."));
        props.put("reason", Map.of("type", "string",
                "description", "Why the access should be removed. Shown to the approver as "
                        + "your stated reason."));
        return Map.of("type", "object", "properties", props,
                "required", List.of("scopeType", "subjectType", "subjectId"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        GrantScopeType scopeType = GrantToolSupport.scopeType(params);
        String scopeId = GrantToolSupport.scopeId(scopeType, ctx, params);
        GrantSubjectType subjectType = GrantToolSupport.subjectType(params);
        String subjectId = GrantToolSupport.req(params, "subjectId");
        String reason = GrantToolSupport.str(params, "reason");

        return support.raise(ctx, PermissionRequestOperation.REVOKE,
                scopeType, scopeId, subjectType, subjectId, /*role*/ null, reason);
    }
}
