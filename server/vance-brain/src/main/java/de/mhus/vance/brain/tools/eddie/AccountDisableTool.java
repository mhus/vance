package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
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
 * Disables an account: it can no longer sign in and frees its seat. Nothing
 * is deleted — documents, roles and teams stay, {@code account_enable} brings
 * it back. Deleting an account is a maintenance operation of the operator
 * shell ({@code user delete}), not a tool.
 *
 * <p>User ADMIN (never someone above the caller's tenant role), and never the
 * caller's own account — the same self-protection as the admin UI.
 */
@Component
@RequiredArgsConstructor
public class AccountDisableTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of("name", Map.of("type", "string", "description", "Username of the account to disable.")),
            "required", List.of("name"));

    private final UserService userService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "account_disable";
    }

    @Override
    public String description() {
        return "Disable a user account: it can no longer sign in and no longer takes a seat. Documents, "
                + "roles and teams are kept; account_enable reverses it. Needs tenant ADMIN; never your own "
                + "account. Ask the user first.";
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
        return "block a user, deactivate an account, someone left the company";
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
        if (name.equals(ctx.userId())) {
            throw new ToolException("Cannot disable your own account");
        }
        admin.enforce(actor, new Resource.User(ctx.tenantId(), name), Action.ADMIN);
        UserDocument current = userService
                .findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException("Account '" + name + "' not found"));
        if (current.getStatus() == UserStatus.DISABLED) {
            throw new ToolException("Account '" + name + "' is already disabled");
        }
        UserDocument saved = userService.update(ctx.tenantId(), name, null, null, UserStatus.DISABLED, null);
        admin.record(
                ctx,
                EddieActivityKind.ACCOUNT_CHANGED,
                "Disabled account `" + name + "`",
                List.of(EntityRef.user(name)));
        return AccountRows.summary(saved, admin.tenantRoleOf(ctx.tenantId(), name));
    }
}
