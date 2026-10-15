package de.mhus.vance.brain.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves an owner's current OAuth access token and refreshes it
 * transparently when it has expired (or is about to). The owner is a user
 * for personal logins, or a project / the tenant for a shared MCP-server
 * login ({@link OAuthTokenOwner}). Locks per
 * {@code (tenantId, owner, providerId)} so concurrent tool calls
 * don't fire ten parallel refresh requests (some providers invalidate
 * the refresh token on use, which would 401 all the racing call sites).
 *
 * <p>Two locks, because there are two kinds of concurrency: a striped
 * monitor serialises the threads of this pod, and a cluster-wide claim
 * ({@link OAuthRefreshClaimService}) the pods. The second matters for shared
 * project/tenant logins, which every pod uses: with a rotating refresh
 * token, a second pod replaying the old one makes the authorization server
 * revoke the whole grant. A pod that finds the claim held waits and re-reads
 * the stored token — the holder's fresh one — instead of refreshing itself.
 *
 * <p>Strategy: read {@code oauth.<provider>.expires_at} from the user-
 * setting cache (it's a non-secret STRING — no Vault roundtrip), bail
 * if still inside {@link #REFRESH_BUFFER}, otherwise take the lock,
 * re-check inside it (double-checked), call
 * {@code provider.refresh(...)}, persist the new tokens, return the
 * fresh access token. Refresh failure → {@link OAuthExpiredException}.
 *
 * <p>Failure modes that all map to {@link OAuthExpiredException}:
 * <ul>
 *   <li>No access token stored at all → user never connected.</li>
 *   <li>Provider config missing, or a confidential client without its
 *       {@code clientSecret} → tenant admin removed the provider while the
 *       user still had tokens.</li>
 *   <li>No refresh token stored (provider didn't issue one, or it was
 *       wiped by a previous failed refresh).</li>
 *   <li>Provider rejected the refresh — revoked / scope-changed /
 *       consent withdrawn.</li>
 * </ul>
 */
@Service
@Slf4j
public class OAuthTokenRefresher {

    /** Refresh when the token is within this window of expiring. */
    public static final Duration REFRESH_BUFFER = Duration.ofSeconds(60);

    /**
     * Age after which another pod may take over a refresh claim — well above
     * the token request timeout, so a live holder is never overtaken.
     */
    static final Duration CLAIM_TTL = Duration.ofSeconds(30);

    /** Pause between two looks at a claim another pod holds. */
    static final Duration CLAIM_POLL = Duration.ofMillis(250);

    private final OAuthConfigRegistry configRegistry;
    private final OAuthTokenStore tokenStore;
    private final OAuthRefreshClaimService claims;
    private final Clock clock;
    private final Duration claimPoll;

    /**
     * Striped lock pool keyed by {@code (tenantId|owner|providerId)}. Fixed
     * size (bounded) rather than one-entry-per-key so it never grows for the
     * pod's lifetime — a hash collision only means two unrelated keys share a
     * monitor (extra, harmless serialization).
     */
    private static final int LOCK_STRIPES = 64;

    private final Object[] locks = newStripes(LOCK_STRIPES);

    private static Object[] newStripes(int n) {
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++) {
            a[i] = new Object();
        }
        return a;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthTokenRefresher(
            OAuthConfigRegistry configRegistry, OAuthTokenStore tokenStore, OAuthRefreshClaimService claims) {
        this(configRegistry, tokenStore, claims, Clock.systemUTC(), CLAIM_POLL);
    }

    OAuthTokenRefresher(
            OAuthConfigRegistry configRegistry,
            OAuthTokenStore tokenStore,
            OAuthRefreshClaimService claims,
            Clock clock,
            Duration claimPoll) {
        this.configRegistry = configRegistry;
        this.tokenStore = tokenStore;
        this.claims = claims;
        this.clock = clock;
        this.claimPoll = claimPoll;
    }

    /**
     * Return a currently-valid access token for {@code (tenant, user,
     * providerId)}, refreshing it if necessary.
     *
     * @throws OAuthExpiredException when no valid token can be produced
     */
    public String resolveAccessToken(String tenantId, String userId, String providerId) {
        return resolveAccessTokenFor(tenantId, OAuthTokenOwner.user(userId), providerId);
    }

    /**
     * Return a currently-valid access token of {@code owner} for
     * {@code providerId}, refreshing it if necessary.
     *
     * @throws OAuthExpiredException when no valid token can be produced
     */
    public String resolveAccessTokenFor(String tenantId, OAuthTokenOwner owner, String providerId) {
        String norm = OAuthProviderLoader.normalizedName(providerId);

        Instant expiresAt = readExpiresAt(tenantId, owner, norm);
        if (!needsRefresh(expiresAt)) {
            String token = tokenStore.accessToken(tenantId, owner, norm);
            if (token != null) return token;
            // No cached token at all — fall through to the refresh path,
            // which will either find a refresh token or fail with
            // OAuthExpiredException.
        }

        synchronized (lockFor(tenantId, owner, norm)) {
            // Double-check inside the lock — a sibling thread may have
            // refreshed while we were queued at the monitor.
            String current = currentValidToken(tenantId, owner, norm);
            if (current != null) return current;
            return refreshClaimed(tenantId, owner, norm);
        }
    }

    /**
     * Refreshes under the cluster-wide claim. While another pod holds it,
     * waits and re-reads: once that pod has persisted its fresh token, the
     * token is returned without a refresh of our own. A holder that died is
     * overtaken after {@link #CLAIM_TTL}.
     */
    private String refreshClaimed(String tenantId, OAuthTokenOwner owner, String providerId) {
        String key = OAuthRefreshClaimService.key(tenantId, owner, providerId);
        // Twice the TTL: the stale-claim takeover happens after one TTL, so
        // giving up means the store keeps refusing us — not a slow holder.
        long maxPolls = CLAIM_TTL.multipliedBy(2).toMillis() / Math.max(1, claimPoll.toMillis());
        String holder = claims.tryClaim(key, CLAIM_TTL);
        for (long polls = 0; holder == null; polls++) {
            if (polls >= maxPolls) {
                throw new IllegalStateException("OAuth token refresh for provider '" + providerId
                        + "' is held by another pod beyond " + CLAIM_TTL.toSeconds() + "s");
            }
            pause();
            String fresh = currentValidToken(tenantId, owner, providerId);
            if (fresh != null) {
                return fresh;
            }
            holder = claims.tryClaim(key, CLAIM_TTL);
        }
        try {
            // Re-read under the claim: another pod may have refreshed
            // between our expiry check and the claim.
            String fresh = currentValidToken(tenantId, owner, providerId);
            if (fresh != null) {
                return fresh;
            }
            return refreshAndPersist(tenantId, owner, providerId);
        } finally {
            claims.release(key, holder);
        }
    }

    /** The stored access token when it is outside the refresh window, else {@code null}. */
    private @Nullable String currentValidToken(String tenantId, OAuthTokenOwner owner, String providerId) {
        if (needsRefresh(readExpiresAt(tenantId, owner, providerId))) {
            return null;
        }
        return tokenStore.accessToken(tenantId, owner, providerId);
    }

    private void pause() {
        try {
            Thread.sleep(claimPoll.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for another pod's OAuth token refresh", e);
        }
    }

    private String refreshAndPersist(String tenantId, OAuthTokenOwner owner, String providerId) {
        String refreshToken = tokenStore.refreshToken(tenantId, owner, providerId);
        if (refreshToken == null) {
            throw new OAuthExpiredException(
                    providerId, "no refresh token stored — user must reconnect provider '" + providerId + "'");
        }

        Optional<ResolvedOAuthProvider> provider = configRegistry.resolve(tenantId, providerId);
        if (provider.isEmpty()) {
            throw new OAuthExpiredException(
                    providerId,
                    "OAuth provider '" + providerId + "' is no longer configured for tenant '" + tenantId + "'");
        }
        if (GenericOAuth2Provider.missesClientSecret(provider.get().config())) {
            throw new OAuthExpiredException(
                    providerId,
                    "OAuth provider '" + providerId + "' has no client secret — "
                            + "tenant admin must restore it before tokens can be refreshed");
        }

        OAuthTokenSet fresh;
        try {
            fresh = provider.get().provider().refresh(provider.get().config(), refreshToken);
        } catch (OAuthFlowException ex) {
            throw new OAuthExpiredException(
                    providerId, "provider rejected refresh — user must reconnect: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new OAuthExpiredException(providerId, "refresh failed with unexpected error: " + ex.getMessage(), ex);
        }
        tokenStore.persist(tenantId, owner, providerId, fresh, /*connect*/ false);
        log.info(
                "OAuthTokenRefresher: refreshed token tenant='{}' owner='{}' provider='{}'",
                tenantId,
                owner.storageProjectId(),
                providerId);
        return fresh.accessToken();
    }

    private boolean needsRefresh(@Nullable Instant expiresAt) {
        if (expiresAt == null) return false; // unknown lifetime — trust the access token
        return clock.instant().plus(REFRESH_BUFFER).isAfter(expiresAt);
    }

    private @Nullable Instant readExpiresAt(String tenantId, OAuthTokenOwner owner, String providerId) {
        String raw = tokenStore.expiresAt(tenantId, owner, providerId);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            log.warn(
                    "OAuthTokenRefresher: bad expires_at value '{}' for tenant='{}' owner='{}' — ignoring",
                    raw,
                    tenantId,
                    owner.storageProjectId());
            return null;
        }
    }

    private Object lockFor(String tenantId, OAuthTokenOwner owner, String providerId) {
        String key = tenantId + "|" + owner.storageProjectId() + "|" + providerId;
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
