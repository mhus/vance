package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Asks for a role to be granted.
 *
 * <p>The tool cannot grant anything: it records the request and routes an
 * approval item. That separation is the point — a tool that acts on the
 * caller's authority acts equally on injected instructions the caller merely
 * read somewhere.
 */
@Component
@RequiredArgsConstructor
public class RoleRequestGrantTool implements Tool {

    private final RoleRequestSupport support;

    @Override
    public String name() {
        return "role_request_grant";
    }

    @Override
    public String description() {
        return "Request a role for a user or team. PROJECT scope: a project membership "
                + "(READER/MEMBER/ADMIN) for a user or team. TENANT scope: a tenant role "
                + "(USER/CREATOR/ADMIN/TENANT_ADMIN) for a user. This does NOT change any permission: "
                + "it creates a request and asks an administrator to approve it. Report to the user "
                + "that approval is pending — do not claim access has been granted.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    /**
     * Same treatment as {@code role_list}, and for a stronger reason: this one
     * puts a CRITICAL item in an admin's inbox. {@code primary()} only steers
     * engines without an allow-list — {@code ContextToolsApi.classify} reads
     * {@link #deferred()} — so without both flags the full schema of a
     * write-initiating tool sits in every classified turn's manifest.
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
        props.put(
                "role",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("READER", "MEMBER", "ADMIN", "USER", "CREATOR", "TENANT_ADMIN"),
                        "description",
                        "PROJECT scope: READER, MEMBER or ADMIN. TENANT scope: USER, CREATOR, ADMIN or TENANT_ADMIN."));
        props.put(
                "reason",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Why this access is needed. Shown to the approver as your stated reason."));
        return Map.of(
                "type",
                "object",
                "properties",
                props,
                "required",
                List.of("scopeType", "subjectType", "subjectId", "role"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        RoleScopeType scopeType = RoleToolSupport.scopeType(params);
        String scopeId = RoleToolSupport.scopeId(scopeType, ctx, params);
        RoleSubjectType subjectType = RoleToolSupport.subjectType(params);
        String subjectId = RoleToolSupport.req(params, "subjectId");
        String reason = RoleToolSupport.str(params, "reason");

        RoleRequestService.Target target;
        if (scopeType == RoleScopeType.TENANT) {
            if (subjectType != RoleSubjectType.USER) {
                throw new ToolException("tenant roles are held by users only — use a PROJECT scope for a team");
            }
            target = RoleRequestService.Target.tenantGrant(scopeId, subjectId, RoleToolSupport.tenantRole(params));
        } else {
            target = RoleRequestService.Target.projectGrant(
                    scopeId, subjectType, subjectId, RoleToolSupport.projectRole(params));
        }
        // No authorization check: raising a request changes nothing, and
        // requiring authority to ask would defeat the purpose — the caller asks
        // precisely because they cannot act themselves.
        return support.raise(ctx, RoleRequestOperation.GRANT, target, reason);
    }
}
