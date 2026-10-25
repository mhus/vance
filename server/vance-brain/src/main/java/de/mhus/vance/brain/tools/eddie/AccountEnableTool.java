package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaService;
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
 * Re-enables a disabled account. Leaving DISABLED makes the account take a
 * seat again (a service account: its own quota), so the tenant's quota is
 * checked first. User ADMIN.
 */
@Component
@RequiredArgsConstructor
public class AccountEnableTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string", "description", "Username of the disabled account.")),
            "required", List.of("name"));

    private final UserService userService;
    private final UserQuotaService quotaService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "account_enable";
    }

    @Override
    public String description() {
        return "Re-enable a disabled user account. Needs tenant ADMIN and a free seat — when the seat quota "
                + "is full the call is refused; explain that instead of retrying.";
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
        return "unblock a user, reactivate a disabled account";
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
        admin.enforce(actor, new Resource.User(ctx.tenantId(), name), Action.ADMIN);
        UserDocument current = userService
                .findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException("Account '" + name + "' not found"));
        if (current.getStatus() != UserStatus.DISABLED) {
            throw new ToolException("Account '" + name + "' is not disabled (status " + current.getStatus() + ")");
        }
        try {
            quotaService.checkStatusChange(ctx.tenantId(), current, UserStatus.ACTIVE);
        } catch (UserQuotaService.UserQuotaExceededException e) {
            throw new ToolException(e.getMessage(), e);
        }
        UserDocument saved = userService.update(ctx.tenantId(), name, null, null, UserStatus.ACTIVE, null);
        admin.record(
                ctx,
                EddieActivityKind.ACCOUNT_CHANGED,
                "Konto `" + name + "` reaktiviert",
                List.of(EntityRef.user(name)));
        return AccountRows.summary(saved, admin.tenantRoleOf(ctx.tenantId(), name));
    }
}
