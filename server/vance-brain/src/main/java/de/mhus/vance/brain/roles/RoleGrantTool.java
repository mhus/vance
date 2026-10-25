package de.mhus.vance.brain.roles;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Grants a role — directly where the invoking user's own authority reaches,
 * as a role request where it does not ({@link RoleChangeSupport}). Nobody
 * hands out more than they hold, so acting on the caller's authority opens
 * nothing the caller could not have done in the admin UI.
 */
@Component
@RequiredArgsConstructor
public class RoleGrantTool implements Tool {

    private static final Map<String, Object> SCHEMA = RoleToolSupport.changeSchema(
            true, "Why this access is needed. Only used when the grant becomes a request for approval.");

    private final RoleChangeSupport changes;

    @Override
    public String name() {
        return "role_grant";
    }

    @Override
    public String description() {
        return "Grant a role. PROJECT scope: a project membership (READER/MEMBER/ADMIN) for a user or team. "
                + "TENANT scope: a tenant role (USER/CREATOR/ADMIN/TENANT_ADMIN) for a user. Applied at once "
                + "when it is within your own authority (applied=true); otherwise a request goes to an "
                + "administrator for approval (requested=true) — then say approval is pending, never that "
                + "access was granted. Ask the user before granting.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "give a user or team a role, make someone project member or admin";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, ToolLabels.WRITE);
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return changes.grant(
                ctx, name(), RoleToolSupport.grantTarget(params, ctx), RoleToolSupport.str(params, "reason"));
    }
}
