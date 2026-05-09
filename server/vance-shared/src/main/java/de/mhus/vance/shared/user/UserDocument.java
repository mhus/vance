package de.mhus.vance.shared.user;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent user record, scoped to a tenant.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name} (not its MongoDB
 * id), per the entity convention in {@code CLAUDE.md}. {@code name} is the
 * technical unique username inside the tenant. {@code title} is an optional
 * display name; {@code passwordHash} is produced by the caller and stored
 * verbatim.
 */
@Document(collection = "users")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String name = "";

    private @Nullable String passwordHash;

    private @Nullable String title;

    private @Nullable String email;

    private UserStatus status = UserStatus.ACTIVE;

    /**
     * Whether the user can authenticate via the password / refresh-token
     * flow at {@code POST /brain/{tenant}/access/{username}}. Service
     * accounts default to {@code false}; human accounts default to
     * {@code true}. Independent of {@link #status} on purpose — a human
     * user may be temporarily prevented from logging in (e.g. lockout)
     * without being marked DISABLED, and a service account is by
     * definition login-blocked but otherwise active.
     *
     * <p>JWT verification (in {@code AccessFilterBase}) does NOT consult
     * this flag — once a token has been minted (e.g. by the Anus admin
     * shell for a service account), it stays valid until expiry.
     */
    @Builder.Default
    private boolean loginEnabled = true;

    /**
     * {@code true} for accounts that exist purely to anchor automated /
     * internal identities — bootstrap-managed admin accounts, third-party
     * integrations, etc. Service accounts cannot use the password login
     * (see {@link #loginEnabled}). The flag is set at creation time by
     * {@link de.mhus.vance.shared.user.UserService#createServiceAccount}
     * and is intentionally immutable afterwards.
     */
    @Builder.Default
    private boolean serviceAccount = false;

    @CreatedDate
    private @Nullable Instant createdAt;
}
