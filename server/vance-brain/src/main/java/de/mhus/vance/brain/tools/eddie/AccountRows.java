package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.user.ExternalIdentity;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The account fields the {@code account_*} tools report. Never the password
 * hash — only whether there is one.
 */
final class AccountRows {

    private AccountRows() {}

    /** One account in a list: who it is, whether it can work, which tenant role it holds. */
    static Map<String, Object> summary(UserDocument user, TenantRole tenantRole) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", user.getName());
        if (user.getTitle() != null) {
            row.put("title", user.getTitle());
        }
        row.put("status", user.getStatus().name());
        row.put("tenantRole", tenantRole.name());
        if (user.isServiceAccount()) {
            row.put("serviceAccount", true);
        }
        String signIn = signIn(user);
        if (signIn != null) {
            row.put("signIn", signIn);
        }
        return row;
    }

    /**
     * How the account signs in: {@code sso} (bound), {@code sso-prepared}
     * (waiting for the first SSO login), {@code password}, or {@code null}
     * when it cannot sign in yet.
     */
    static @Nullable String signIn(UserDocument user) {
        ExternalIdentity external = user.getExternalIdentity();
        if (external != null) {
            return external.getSubject() == null ? "sso-prepared" : "sso";
        }
        return user.getPasswordHash() == null ? null : "password";
    }

    static Map<String, Object> usage(UserQuotaService.Usage usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", usage.active());
        out.put("limit", usage.limit() <= 0 ? "unlimited" : usage.limit());
        return out;
    }
}
