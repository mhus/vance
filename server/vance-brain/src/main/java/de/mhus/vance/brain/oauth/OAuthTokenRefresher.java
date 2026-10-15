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

    private final OAuthConfigRegistry configRegistry;
    private final OAuthTokenStore tokenStore;
    private final Clock clock;

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
    public OAuthTokenRefresher(OAuthConfigRegistry configRegistry, OAuthTokenStore tokenStore) {
        this(configRegistry, tokenStore, Clock.systemUTC());
    }

    OAuthTokenRefresher(OAuthConfigRegistry configRegistry, OAuthTokenStore tokenStore, Clock clock) {
        this.configRegistry = configRegistry;
        this.tokenStore = tokenStore;
        this.clock = clock;
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
            expiresAt = readExpiresAt(tenantId, owner, norm);
            if (!needsRefresh(expiresAt)) {
                String token = tokenStore.accessToken(tenantId, owner, norm);
                if (token != null) return token;
            }
            return refreshAndPersist(tenantId, owner, norm);
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
