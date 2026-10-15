package de.mhus.vance.brain.oauth;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persistence of OAuth tokens as settings on the owner's storage project
 * ({@link OAuthTokenOwner}). One place for the key layout, shared by the
 * connect callback and the token refresher:
 *
 * <ul>
 *   <li>{@code oauth.<providerId>.access_token} — PASSWORD</li>
 *   <li>{@code oauth.<providerId>.refresh_token} — PASSWORD, when issued</li>
 *   <li>{@code oauth.<providerId>.expires_at} — STRING, ISO-8601</li>
 *   <li>{@code oauth.<providerId>.scopes} — STRING, space-separated</li>
 *   <li>{@code oauth.<providerId>.extra} — STRING, JSON blob of the
 *       non-secret extra claims</li>
 *   <li>{@code oauth.<providerId>.<claim>} — flat projection of each scalar
 *       extra claim (PASSWORD for secret-bearing claims) so tool templates
 *       can reference it directly</li>
 * </ul>
 *
 * <p>The same namespace also carries the provider's <em>client</em>
 * configuration on the {@code _tenant} project —
 * {@code oauth.<providerId>.client_secret}
 * ({@link OAuthProviderLoader#clientSecretKey}). For a tenant-wide login
 * the tokens sit right next to it, so neither {@link #wipe} nor the
 * flat-extra projection of {@link #persist} ever touches that key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthTokenStore {

    static final String KEY_ACCESS_TOKEN = "access_token";
    static final String KEY_REFRESH_TOKEN = "refresh_token";
    static final String KEY_EXPIRES_AT = "expires_at";
    static final String KEY_SCOPES = "scopes";
    static final String KEY_EXTRA = "extra";

    /**
     * Key suffixes the flat-extra projection never writes: the token keys
     * above and the client secret. A token response is foreign input — a
     * claim named {@code client_secret} must not overwrite the client
     * registration of a tenant login, nor one named {@code refresh_token}
     * a stored token.
     */
    private static final Set<String> RESERVED_SUFFIXES = Set.of(
            KEY_ACCESS_TOKEN,
            KEY_REFRESH_TOKEN,
            KEY_EXPIRES_AT,
            KEY_SCOPES,
            KEY_EXTRA,
            OAuthProviderLoader.CLIENT_SECRET_KEY_SUFFIX.substring(1));

    private final SettingService settingService;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** Setting key {@code oauth.<providerId>.<suffix>}. */
    public static String key(String providerId, String suffix) {
        return "oauth." + providerId + "." + suffix;
    }

    public @Nullable String accessToken(String tenantId, OAuthTokenOwner owner, String providerId) {
        return readSecret(tenantId, owner, key(providerId, KEY_ACCESS_TOKEN));
    }

    public @Nullable String refreshToken(String tenantId, OAuthTokenOwner owner, String providerId) {
        return readSecret(tenantId, owner, key(providerId, KEY_REFRESH_TOKEN));
    }

    public @Nullable String expiresAt(String tenantId, OAuthTokenOwner owner, String providerId) {
        String k = key(providerId, KEY_EXPIRES_AT);
        if (owner.kind() == OAuthTokenOwner.Kind.USER) {
            return settingService.getUserStringValue(tenantId, userOf(owner), k);
        }
        return settingService.getStringValue(tenantId, SettingService.SCOPE_PROJECT, owner.storageProjectId(), k);
    }

    public boolean isConnected(String tenantId, OAuthTokenOwner owner, String providerId) {
        return accessToken(tenantId, owner, providerId) != null;
    }

    /**
     * Removes the login of the owner: every {@code oauth.<providerId>.*}
     * setting on its storage project — tokens, expiry, scopes and the
     * flat-extra projections — <b>except</b> the client secret. On
     * {@code _tenant} that key belongs to the provider's client
     * registration, not to the login; deleting it would make every later
     * login of a confidential client fail with {@code invalid_client}.
     *
     * @return the number of removed settings
     */
    public long wipe(String tenantId, OAuthTokenOwner owner, String providerId) {
        String prefix = "oauth." + providerId + ".";
        String clientSecretKey = OAuthProviderLoader.clientSecretKey(providerId);
        long removed = 0;
        for (SettingDocument setting :
                settingService.findAll(tenantId, SettingService.SCOPE_PROJECT, owner.storageProjectId())) {
            String k = setting.getKey();
            if (k.startsWith(prefix) && !k.equals(clientSecretKey)) {
                settingService.delete(tenantId, SettingService.SCOPE_PROJECT, owner.storageProjectId(), k);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Writes a token set.
     *
     * @param connect {@code true} for a fresh login: a refresh token or expiry
     *                the new response does not carry is removed (provider
     *                downgrade, scope change). {@code false} for a refresh:
     *                absent values are kept — many providers omit the refresh
     *                token on rotation while the old one stays valid.
     */
    public void persist(
            String tenantId, OAuthTokenOwner owner, String providerId, OAuthTokenSet tokens, boolean connect) {
        String ref = owner.storageProjectId();
        settingService.setEncryptedPassword(
                tenantId, SettingService.SCOPE_PROJECT, ref, key(providerId, KEY_ACCESS_TOKEN), tokens.accessToken());

        if (tokens.refreshToken() != null) {
            settingService.setEncryptedPassword(
                    tenantId,
                    SettingService.SCOPE_PROJECT,
                    ref,
                    key(providerId, KEY_REFRESH_TOKEN),
                    tokens.refreshToken());
        } else if (connect) {
            settingService.delete(tenantId, SettingService.SCOPE_PROJECT, ref, key(providerId, KEY_REFRESH_TOKEN));
        }

        if (tokens.expiresAt() != null) {
            settingService.set(
                    tenantId,
                    SettingService.SCOPE_PROJECT,
                    ref,
                    key(providerId, KEY_EXPIRES_AT),
                    tokens.expiresAt().toString(),
                    SettingType.STRING,
                    null);
        } else if (connect) {
            settingService.delete(tenantId, SettingService.SCOPE_PROJECT, ref, key(providerId, KEY_EXPIRES_AT));
        }

        if (tokens.extraClaims().isEmpty()) {
            return;
        }
        Map<String, String> nonScope = new LinkedHashMap<>(tokens.extraClaims());
        String scopes = nonScope.remove("scope");
        if (scopes != null && !scopes.isEmpty()) {
            settingService.set(
                    tenantId,
                    SettingService.SCOPE_PROJECT,
                    ref,
                    key(providerId, KEY_SCOPES),
                    scopes,
                    SettingType.STRING,
                    null);
        }
        if (nonScope.isEmpty()) {
            return;
        }
        // The JSON blob is STRING (unencrypted) — secret-bearing claims
        // (bot_access_token, id_token, …) stay out of it; they are stored
        // encrypted via the flat projection below (F4).
        Map<String, String> nonSecret = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : nonScope.entrySet()) {
            if (!OAuthTokenSet.isSecretClaimKey(e.getKey())) {
                nonSecret.put(e.getKey(), e.getValue());
            }
        }
        if (nonSecret.isEmpty()) {
            settingService.delete(tenantId, SettingService.SCOPE_PROJECT, ref, key(providerId, KEY_EXTRA));
        } else {
            try {
                settingService.set(
                        tenantId,
                        SettingService.SCOPE_PROJECT,
                        ref,
                        key(providerId, KEY_EXTRA),
                        json.writeValueAsString(nonSecret),
                        SettingType.STRING,
                        null);
            } catch (RuntimeException ex) {
                log.warn("OAuth: failed to serialise extra claims for '{}': {}", providerId, ex.toString());
            }
        }
        // Flat projection so tool templates can reference
        // {{secret:user:oauth.<provider>.<extraKey>}} without parsing the
        // blob (cloud_id, site_url, …). JSON-valued non-secret strings stay
        // in the blob only.
        for (Map.Entry<String, String> e : nonScope.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (RESERVED_SUFFIXES.contains(e.getKey())) {
                log.warn(
                        "OAuth: token response of '{}' carries reserved claim '{}' — not projected",
                        providerId,
                        e.getKey());
                continue;
            }
            if (OAuthTokenSet.isSecretClaimKey(e.getKey())) {
                settingService.setEncryptedPassword(
                        tenantId, SettingService.SCOPE_PROJECT, ref, key(providerId, e.getKey()), v);
            } else if (!looksLikeJsonContainer(v)) {
                settingService.set(
                        tenantId,
                        SettingService.SCOPE_PROJECT,
                        ref,
                        key(providerId, e.getKey()),
                        v,
                        SettingType.STRING,
                        null);
            }
        }
    }

    private @Nullable String readSecret(String tenantId, OAuthTokenOwner owner, String key) {
        if (owner.kind() == OAuthTokenOwner.Kind.USER) {
            return settingService.getDecryptedUserPassword(tenantId, userOf(owner), key);
        }
        return settingService.getDecryptedPassword(
                tenantId, SettingService.SCOPE_PROJECT, owner.storageProjectId(), key);
    }

    private static String userOf(OAuthTokenOwner owner) {
        return owner.storageProjectId().substring(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length());
    }

    /** Crude JSON-array/object detector — keeps blobs out of the flat-extra projection. */
    private static boolean looksLikeJsonContainer(String s) {
        if (s.length() < 2) return false;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }
}
