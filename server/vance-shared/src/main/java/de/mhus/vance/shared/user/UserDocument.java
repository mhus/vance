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
@Document(collection = UserDocument.COLLECTION)
@CompoundIndexes({
    @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true),
    // One account per external subject, installation-wide. Partial, not
    // sparse: prepared accounts carry the issuer without a subject, and a
    // sparse compound index would still index them — as duplicates.
    @CompoundIndex(
            name = "external_subject_idx",
            def = "{ 'externalIdentity.issuer': 1, 'externalIdentity.subject': 1 }",
            unique = true,
            partialFilter = "{ 'externalIdentity.subject': { $type: 'string' } }"),
    // An address is prepared for at most one account per tenant.
    @CompoundIndex(
            name = "external_expected_email_idx",
            def = "{ 'tenantId': 1, 'externalIdentity.expectedEmail': 1 }",
            unique = true,
            partialFilter = "{ 'externalIdentity.expectedEmail': { $type: 'string' } }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDocument {

    /**
     * Collection name, so callers that need it as a string — the
     * project-maintenance handler declaring what it writes to — do not restate
     * it next to the annotation that decides it.
     */
    public static final String COLLECTION = "users";

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

    /**
     * Number of consecutive failed login attempts since the last success.
     * Reset to zero on a successful login and whenever the password is
     * changed. Once it reaches the lockout threshold, {@link #lockedUntil}
     * is set and this counter is reset so a fresh window starts after the
     * lock expires. Managed atomically by
     * {@link UserService#recordFailedLogin} /
     * {@link UserService#resetLoginFailures}.
     *
     * <p>Deliberately login-generic (not password-specific): a future
     * second-factor failure path can share the same counter.
     */
    @Builder.Default
    private int failedLoginAttempts = 0;

    /**
     * If set and in the future, the account is temporarily locked out and
     * password logins are refused until this instant passes (auto-unlock —
     * no admin action needed). {@code null} means not locked.
     */
    private @Nullable Instant lockedUntil;

    /** Timestamp of the most recent failed login attempt, for diagnostics. */
    private @Nullable Instant lastFailedLoginAt;

    /**
     * When the password hash was last set / changed. Carried for future use
     * (password age display, optional expiry); not enforced today.
     */
    private @Nullable Instant passwordChangedAt;

    /**
     * Login tokens (ACCESS, REFRESH) issued at or before this instant are void
     * for this account — checked by the access filter and by every re-mint.
     * Set when the account's way in changes such that earlier sessions must not
     * survive it: {@link UserService#prepareExternalIdentity} stamps it, so no
     * session started with the old password outlives the switch to SSO (and
     * none comes back after a later unlink). {@code null} means no floor.
     *
     * <p>Deliberately separate from {@link #passwordChangedAt}: a user changing
     * their own password must not be thrown out of the session they changed it
     * in.
     */
    private @Nullable Instant tokensNotBefore;

    /**
     * The external sign-in (SSO) this account is prepared for or bound to;
     * {@code null} for a password account. See {@link ExternalIdentity}.
     */
    private @Nullable ExternalIdentity externalIdentity;

    @CreatedDate
    private @Nullable Instant createdAt;

    /**
     * Whether a login token issued at {@code issuedAt} is still acceptable
     * under {@link #tokensNotBefore}. JWT {@code iat} has whole seconds, so the
     * floor is compared at second granularity and a token from the floor's own
     * second counts as before it — a session cannot start in the same second
     * the account switched its way in.
     */
    public boolean acceptsTokenIssuedAt(@Nullable Instant issuedAt) {
        return issuedAfterFloor(tokensNotBefore, issuedAt);
    }

    /** {@link #acceptsTokenIssuedAt} for a floor read elsewhere (e.g. a cache). */
    public static boolean issuedAfterFloor(@Nullable Instant tokensNotBefore, @Nullable Instant issuedAt) {
        if (tokensNotBefore == null) {
            return true;
        }
        return issuedAt != null && issuedAt.getEpochSecond() > tokensNotBefore.getEpochSecond();
    }
}
