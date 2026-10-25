package de.mhus.vance.brain.roles;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Revokes a role — directly where the invoking user's own authority reaches,
 * as a role request where it does not ({@link RoleChangeSupport}). A tenant
 * role falls back to USER; a project membership is removed.
 */
@Component
@RequiredArgsConstructor
public class RoleRevokeTool implements Tool {

    private static final Map<String, Object> SCHEMA = RoleToolSupport.changeSchema(
            false, "Why the access should end. Only used when the revoke becomes a request for approval.");

    private final RoleChangeSupport changes;

    @Override
    public String name() {
        return "role_revoke";
    }

    @Override
    public String description() {
        return "Revoke a role. PROJECT scope: remove a user's or team's project membership. TENANT scope: "
                + "set a user's tenant role back to USER. Applied at once when it is within your own "
                + "authority (applied=true); otherwise a request goes to an administrator (requested=true). "
                + "Ask the user before revoking.";
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
        return "take a role away, remove someone from a project";
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
        return changes.revoke(
                ctx, name(), RoleToolSupport.revokeTarget(params, ctx), RoleToolSupport.str(params, "reason"));
    }
}
