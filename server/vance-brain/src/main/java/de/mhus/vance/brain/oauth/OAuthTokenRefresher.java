package de.mhus.vance.brain.oauth;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves a user's current OAuth access token and refreshes it
 * transparently when it has expired (or is about to). Locks per
 * {@code (tenantId, userId, providerId)} so concurrent tool calls
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
 *   <li>Provider config missing or {@code clientSecret} empty → tenant
 *       admin removed the provider while the user still had tokens.</li>
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
    private final SettingService settingService;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** Per-{@code (tenantId|userId|providerId)} lock pool. */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthTokenRefresher(
            OAuthConfigRegistry configRegistry,
            SettingService settingService) {
        this(configRegistry, settingService, Clock.systemUTC());
    }

    OAuthTokenRefresher(
            OAuthConfigRegistry configRegistry,
            SettingService settingService,
            Clock clock) {
        this.configRegistry = configRegistry;
        this.settingService = settingService;
        this.clock = clock;
    }

    /**
     * Return a currently-valid access token for {@code (tenant, user,
     * providerId)}, refreshing it if necessary.
     *
     * @throws OAuthExpiredException when no valid token can be produced
     */
    public String resolveAccessToken(String tenantId, String userId, String providerId) {
        String norm = OAuthProviderLoader.normalizedName(providerId);
        String accessKey = oauthKey(norm, "access_token");
        String expiresKey = oauthKey(norm, "expires_at");

        Instant expiresAt = readExpiresAt(tenantId, userId, expiresKey);
        if (!needsRefresh(expiresAt)) {
            String token = settingService.getDecryptedUserPassword(tenantId, userId, accessKey);
            if (token != null) return token;
            // No cached token at all — fall through to the refresh path,
            // which will either find a refresh token or fail with
            // OAuthExpiredException.
        }

        synchronized (lockFor(tenantId, userId, norm)) {
            // Double-check inside the lock — a sibling thread may have
            // refreshed while we were queued at the monitor.
            expiresAt = readExpiresAt(tenantId, userId, expiresKey);
            if (!needsRefresh(expiresAt)) {
                String token = settingService.getDecryptedUserPassword(
                        tenantId, userId, accessKey);
                if (token != null) return token;
            }
            return refreshAndPersist(tenantId, userId, norm);
        }
    }

    private String refreshAndPersist(String tenantId, String userId, String providerId) {
        String refreshKey = oauthKey(providerId, "refresh_token");
        String refreshToken = settingService.getDecryptedUserPassword(
                tenantId, userId, refreshKey);
        if (refreshToken == null) {
            throw new OAuthExpiredException(providerId,
                    "no refresh token stored — user must reconnect provider '"
                            + providerId + "'");
        }

        Optional<ResolvedOAuthProvider> provider = configRegistry.resolve(tenantId, providerId);
        if (provider.isEmpty()) {
            throw new OAuthExpiredException(providerId,
                    "OAuth provider '" + providerId + "' is no longer configured for tenant '"
                            + tenantId + "'");
        }
        if (provider.get().config().clientSecret().isEmpty()) {
            throw new OAuthExpiredException(providerId,
                    "OAuth provider '" + providerId + "' has no client secret — "
                            + "tenant admin must restore it before tokens can be refreshed");
        }

        OAuthTokenSet fresh;
        try {
            fresh = provider.get().provider().refresh(
                    provider.get().config(), refreshToken);
        } catch (OAuthFlowException ex) {
            throw new OAuthExpiredException(providerId,
                    "provider rejected refresh — user must reconnect: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new OAuthExpiredException(providerId,
                    "refresh failed with unexpected error: " + ex.getMessage(), ex);
        }
        persistTokens(tenantId, userId, providerId, fresh);
        log.info("OAuthTokenRefresher: refreshed token tenant='{}' user='{}' provider='{}'",
                tenantId, userId, providerId);
        return fresh.accessToken();
    }

    private boolean needsRefresh(Instant expiresAt) {
        if (expiresAt == null) return false;  // unknown lifetime — trust the access token
        return clock.instant().plus(REFRESH_BUFFER).isAfter(expiresAt);
    }

    private Instant readExpiresAt(String tenantId, String userId, String expiresKey) {
        String raw = settingService.getUserStringValue(tenantId, userId, expiresKey);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            log.warn("OAuthTokenRefresher: bad expires_at value '{}' for tenant='{}' user='{}' — ignoring",
                    raw, tenantId, userId);
            return null;
        }
    }

    private void persistTokens(
            String tenantId, String userId, String providerId, OAuthTokenSet tokens) {
        String userRef = HomeBootstrapService.hubProjectName(userId);
        settingService.setEncryptedPassword(tenantId, SettingService.SCOPE_PROJECT, userRef,
                oauthKey(providerId, "access_token"), tokens.accessToken());

        if (tokens.refreshToken() != null) {
            settingService.setEncryptedPassword(tenantId, SettingService.SCOPE_PROJECT, userRef,
                    oauthKey(providerId, "refresh_token"), tokens.refreshToken());
        }
        // We do NOT delete the existing refresh token when fresh.refreshToken()
        // is null — many providers omit a fresh refresh on rotation only when
        // the old one stays valid (token rotation policy varies). The connect
        // flow already wipes stale refreshes; refresh-time should be conservative.

        if (tokens.expiresAt() != null) {
            settingService.set(tenantId, SettingService.SCOPE_PROJECT, userRef,
                    oauthKey(providerId, "expires_at"),
                    tokens.expiresAt().toString(), SettingType.STRING, null);
        }
        if (!tokens.extraClaims().isEmpty()) {
            Map<String, String> nonScope = new LinkedHashMap<>(tokens.extraClaims());
            String scopes = nonScope.remove("scope");
            if (scopes != null && !scopes.isEmpty()) {
                settingService.set(tenantId, SettingService.SCOPE_PROJECT, userRef,
                        oauthKey(providerId, "scopes"), scopes, SettingType.STRING, null);
            }
            if (!nonScope.isEmpty()) {
                try {
                    settingService.set(tenantId, SettingService.SCOPE_PROJECT, userRef,
                            oauthKey(providerId, "extra"),
                            json.writeValueAsString(nonScope),
                            SettingType.STRING, null);
                } catch (RuntimeException ex) {
                    log.warn("OAuthTokenRefresher: failed to serialise extra claims for '{}': {}",
                            providerId, ex.toString());
                }
            }
        }
    }

    private Object lockFor(String tenantId, String userId, String providerId) {
        return locks.computeIfAbsent(tenantId + "|" + userId + "|" + providerId,
                k -> new Object());
    }

    private static String oauthKey(String providerId, String suffix) {
        return "oauth." + providerId + "." + suffix;
    }
}
