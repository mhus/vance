package de.mhus.vance.shared.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The registry row behind one
 * {@link de.mhus.vance.shared.jwt.TokenType#INTEGRATION} token.
 *
 * <p><b>This row is the reason a long-lived token is acceptable at all.</b> A
 * JWT is self-contained: once signed it is valid until it expires, and there is
 * no way to take it back. Pairing it with a row keyed by {@code jti} splits the
 * credential in two — the signed half carries the claims, this half carries
 * whether it is still alive. Revoking is a field update; the token in the wild
 * stops working on the next request.
 *
 * <p>There is deliberately <b>no TTL index</b> on {@link #expiresAt}. An expired
 * row must stay: it is what the owner sees in "my tokens", and evicting it would
 * turn an expired token into an unknown one — which, by the fail-closed rule in
 * {@code IntegrationTokenService}, is the same answer but a worse explanation.
 *
 * <p>What is <em>not</em> stored is the token itself. Nothing here needs to
 * reproduce it, and a mint surface that could show you the token again is a
 * copy of a credential sitting in a database.
 */
@Document(collection = "integration_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationTokenDocument {

    @Id
    private @Nullable String id;

    /**
     * The {@code jti} claim of the minted token — the lookup key of the
     * per-request revocation check. Unique because two rows for one token id
     * would make "is it revoked" ambiguous.
     */
    @Indexed(unique = true)
    private String tokenId = "";

    /** Tenant the token authenticates into. */
    @Indexed
    private String tenantId = "";

    /** The account the token acts as. Its grants remain the ceiling. */
    private String userId = "";

    /**
     * Ids of the {@code IntegrationScopeProfile}s this token is limited to.
     * A list because one outside tool routinely does more than one thing and
     * must still be set up once.
     */
    private List<String> scopeProfiles = new ArrayList<>();

    /** Project the token is pinned to, or {@code null} for an unpinned one. */
    private @Nullable String projectId;

    /** What the owner called it, so a token list is readable a year later. */
    private String label = "";

    private @Nullable Instant createdAt;

    /** Who minted it — normally the owner themselves. */
    private @Nullable String createdBy;

    /**
     * The OAuth grant whose {@code account} login minted this token, or
     * {@code null} when it was minted with a regular login. A token made
     * through an app login is a child of that grant: revoking the grant
     * revokes it too, otherwise disconnecting an app would leave behind the
     * credentials the app created.
     */
    @Indexed(sparse = true)
    private @Nullable String parentGrantId;

    /** Safety-net expiry, mirrored from the token's {@code exp}. */
    private @Nullable Instant expiresAt;

    /** Set when revoked; any value here means the token is dead. */
    private @Nullable Instant revokedAt;

    /**
     * Idle window for a sliding-lifetime token, in days. Set at mint time for
     * tokens of a profile that opts into sliding lifetime
     * ({@code IntegrationScopeProfile.slidingLifetime() = true}); {@code null}
     * for every fixed-end token. After this many days without a successful
     * {@code POST backup/runs} (or whichever endpoint is the liveness signal for
     * the profile), the token is treated as dead — a fresh {@code extend()}
     * call on every successful run sets {@link #expiresAt} to {@code now +
     * idleDays}, so a long-running tool never trips over its own fixed
     * expiry. See {@code planning/user-backup.md} §7.6.
     */
    private @Nullable Integer idleDays;

    /**
     * Last time the token authenticated a request. Written lazily (see
     * {@code IntegrationTokenService}) — this is how a leaked or forgotten
     * token becomes visible at all, so it is worth the occasional write, but
     * not one per request.
     */
    private @Nullable Instant lastUsedAt;

    /**
     * IP of the most recent request that authenticated with this token.
     * Set by the same lazy write as {@link #lastUsedAt} — used by the
     * "Apps with Zugriff" view to surface a second IP that suggests the
     * token was stolen. {@code null} when the liveness row was never
     * updated (token just minted, no request yet) or when the caller did
     * not provide one.
     */
    private @Nullable String lastUsedIp;
}
