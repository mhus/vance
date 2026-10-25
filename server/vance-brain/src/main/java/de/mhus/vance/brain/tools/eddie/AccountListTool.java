package de.mhus.vance.brain.tools.eddie;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lists the accounts of the tenant with status and tenant role, plus how much
 * of the account quotas is used. Tenant ADMIN, as the admin user list.
 *
 * <p>Vance-internal {@code _vance-*} identities and tombstones of deleted
 * accounts are left out — neither is anyone the user manages.
 */
@Component
@RequiredArgsConstructor
public class AccountListTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "query",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional filter: part of the username, display name or email."),
                            "status",
                                    Map.of(
                                            "type",
                                            "string",
                                            "enum",
                                            List.of("ACTIVE", "DISABLED", "PENDING"),
                                            "description",
                                            "Optional: only accounts with this status."),
                            "limit",
                                    Map.of(
                                            "type",
                                            "integer",
                                            "description",
                                            "Maximum rows (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").")),
            "required", List.of());

    private final UserService userService;
    private final UserQuotaService quotaService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "account_list";
    }

    @Override
    public String description() {
        return "List the user accounts of the tenant: name, title, status, tenant role, sign-in method — "
                + "plus the seat and service-account quota usage. Needs tenant ADMIN. "
                + "account_describe shows one account with its project roles and teams.";
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
        return "list users, who has an account, user accounts of the tenant";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, "read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext actor = admin.actor(ctx, name());
        admin.enforce(actor, new Resource.Tenant(ctx.tenantId()), Action.WRITE);

        String query = AdminToolSupport.str(params, "query");
        UserStatus status = parseStatus(AdminToolSupport.str(params, "status"));
        int limit = limit(params);

        List<UserDocument> page = userService.search(ctx.tenantId(), status, query, limit);
        long total = userService.countSearch(ctx.tenantId(), status, query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserDocument u : page) {
            rows.add(AccountRows.summary(u, admin.tenantRoleOf(ctx.tenantId(), u.getName())));
        }

        UserQuotaService.Status quota = quotaService.status(ctx.tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts", rows);
        out.put("total", total);
        if (total > rows.size()) {
            out.put("truncated", true);
        }
        out.put("seats", AccountRows.usage(quota.seats()));
        out.put("serviceAccounts", AccountRows.usage(quota.serviceAccounts()));
        return out;
    }

    private static @Nullable UserStatus parseStatus(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid status '" + raw + "' — expected ACTIVE, DISABLED or PENDING", e);
        }
    }

    private static int limit(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("limit");
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int n;
        try {
            n = raw instanceof Number num
                    ? num.intValue()
                    : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'limit' must be a number", e);
        }
        return Math.max(1, Math.min(MAX_LIMIT, n));
    }
}
