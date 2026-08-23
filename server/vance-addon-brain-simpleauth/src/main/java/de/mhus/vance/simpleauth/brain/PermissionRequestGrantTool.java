package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantRole;
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
 * Asks for a role to be granted. Replaces the former
 * {@code permission_grant_set}, which performed the change directly.
 *
 * <p>The tool cannot grant anything: it records the request and routes an
 * approval item. That separation is the point — a tool that acts on the
 * caller's authority acts equally on injected instructions the caller
 * merely read somewhere.
 */
@Component
@RequiredArgsConstructor
public class PermissionRequestGrantTool implements Tool {

    private final PermissionRequestSupport support;

    @Override
    public String name() {
        return "permission_request_grant";
    }

    @Override
    public String description() {
        return "Request that a role (READER/WRITER/ADMIN) be granted to a user or team on a "
                + "tenant- or project-scope. This does NOT change any permission: it creates "
                + "a request and asks an administrator to approve it. Report to the user that "
                + "approval is pending — do not claim access has been granted.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    /**
     * Same treatment as the read-only {@code permission_grant_list}, and
     * for a stronger reason: this one puts a CRITICAL item in an admin's
     * inbox. {@code primary()} only steers engines without an allow-list —
     * {@code ContextToolsApi.classify} reads {@link #deferred()} — so
     * without both flags the full schema of a write-initiating tool sits
     * in every classified turn's manifest while the harmless read tool is
     * correctly held back.
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
        props.put("role", Map.of("type", "string", "enum", List.of("READER", "WRITER", "ADMIN"),
                "description", "The role to request."));
        props.put("reason", Map.of("type", "string",
                "description", "Why this access is needed. Shown to the approver as your "
                        + "stated reason."));
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
        String reason = GrantToolSupport.str(params, "reason");

        // No authorization check: raising a request changes nothing, and
        // requiring ADMIN to ask would defeat the purpose — the caller
        // asks precisely because they cannot act themselves.
        return support.raise(ctx, PermissionRequestOperation.GRANT,
                scopeType, scopeId, subjectType, subjectId, role, reason);
    }
}
