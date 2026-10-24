package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Asks for a role to be removed.
 *
 * <p>Revoking is approval-gated for the same reason granting is. It looks like
 * the harmless direction, but an unwanted revoke is a clean denial of service —
 * in the worst case it removes the last administrator of a scope, after which
 * nobody can approve anything, including putting it back.
 */
@Component
@RequiredArgsConstructor
public class RoleRequestRevokeTool implements Tool {

    private final RoleRequestSupport support;

    @Override
    public String name() {
        return "role_request_revoke";
    }

    @Override
    public String description() {
        return "Request that a user's or team's role on a tenant or project be removed (a tenant role "
                + "falls back to USER). This does NOT change any permission: it creates a request and asks "
                + "an administrator to approve it. Report to the user that approval is pending — do not "
                + "claim access has been removed.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    /** Same treatment as {@code role_request_grant} — see the note there. */
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
                        "Project name for PROJECT scope (defaults to the current project). Ignored for TENANT."));
        props.put(
                "subjectType",
                Map.of("type", "string", "enum", List.of("USER", "TEAM"), "description", "USER or TEAM."));
        props.put("subjectId", Map.of("type", "string", "description", "Username or team name."));
        props.put(
                "reason",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Why the access should be removed. Shown to the approver as your stated reason."));
        return Map.of(
                "type", "object", "properties", props, "required", List.of("scopeType", "subjectType", "subjectId"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        RoleScopeType scopeType = RoleToolSupport.scopeType(params);
        String scopeId = RoleToolSupport.scopeId(scopeType, ctx, params);
        RoleSubjectType subjectType = RoleToolSupport.subjectType(params);
        String subjectId = RoleToolSupport.req(params, "subjectId");
        String reason = RoleToolSupport.str(params, "reason");

        return support.raise(
                ctx,
                RoleRequestOperation.REVOKE,
                RoleRequestService.Target.revoke(scopeType, scopeId, subjectType, subjectId),
                reason);
    }
}
