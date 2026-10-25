package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Changes an account's display name and email. Status has its own tools
 * ({@code account_disable}/{@code account_enable}), roles theirs
 * ({@code role_grant}/{@code role_revoke}). User ADMIN — which also refuses
 * an account whose tenant role is above the caller's.
 */
@Component
@RequiredArgsConstructor
public class AccountUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name", Map.of("type", "string", "description", "Username of the account."),
                            "title", Map.of("type", "string", "description", "New display name."),
                            "email", Map.of("type", "string", "description", "New email address.")),
            "required", List.of("name"));

    private final UserService userService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "account_update";
    }

    @Override
    public String description() {
        return "Change a user account's display name (title) and/or email. Needs tenant ADMIN. "
                + "For status use account_disable/account_enable, for roles role_grant/role_revoke.";
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
        return "rename a user's display name, change a user's email";
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
        SecurityContext actor = admin.actor(ctx, name());
        String name = AdminToolSupport.req(params, "name");
        String title = AdminToolSupport.str(params, "title");
        String email = AdminToolSupport.str(params, "email");
        if (title == null && email == null) {
            throw new ToolException("nothing to change — pass 'title' and/or 'email'");
        }
        admin.enforce(actor, new Resource.User(ctx.tenantId(), name), Action.ADMIN);
        UserDocument saved;
        try {
            saved = userService.update(ctx.tenantId(), name, title, email, null, null);
        } catch (UserService.UserNotFoundException e) {
            throw new ToolException("Account '" + name + "' not found", e);
        }
        admin.record(
                ctx,
                EddieActivityKind.ACCOUNT_CHANGED,
                "Changed account `" + name + "`",
                List.of(EntityRef.user(name)));
        Map<String, Object> out = AccountRows.summary(saved, admin.tenantRoleOf(ctx.tenantId(), name));
        if (saved.getEmail() != null) {
            out.put("email", saved.getEmail());
        }
        return out;
    }
}
