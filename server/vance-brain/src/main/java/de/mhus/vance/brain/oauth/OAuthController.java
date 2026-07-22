package de.mhus.vance.brain.oauth;

import de.mhus.vance.api.oauth.OAuthProviderListEntry;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * User-facing OAuth endpoints — list of configured providers, init
 * (redirect to provider), callback (consume code, persist tokens).
 *
 * <p>The endpoints are tenant-scoped under {@code /brain/{tenant}/oauth};
 * the tenant path variable is validated by the brain access filter
 * against the caller's JWT before requests reach this controller.
 * The callback explicitly re-validates state against the authenticated
 * user as a defense-in-depth CSRF / confused-deputy guard.
 *
 * <p>Per-user tokens are persisted via {@link SettingService} under
 * the conventional keys {@code oauth.<providerId>.access_token} (PASSWORD),
 * {@code oauth.<providerId>.refresh_token} (PASSWORD when issued),
 * {@code oauth.<providerId>.expires_at} (STRING ISO-8601),
 * {@code oauth.<providerId>.scopes} (STRING space-separated) and
 * {@code oauth.<providerId>.extra} (STRING JSON-blob).
 */
@RestController
@RequestMapping("/brain/{tenant}/oauth")
@Slf4j
public class OAuthController {

    /** User-setting-key prefix for per-provider token data. */
    static final String USER_TOKEN_KEY_PREFIX = "oauth.";

    static final String KEY_ACCESS_TOKEN = ".access_token";
    static final String KEY_REFRESH_TOKEN = ".refresh_token";
    static final String KEY_EXPIRES_AT = ".expires_at";
    static final String KEY_SCOPES = ".scopes";
    static final String KEY_EXTRA = ".extra";

    /** PKCE verifier length — 32 bytes Base64URL ≈ 43 chars (RFC 7636 §4.1 minimum). */
    private static final int PKCE_VERIFIER_BYTES = 32;

    private final OAuthConfigRegistry configRegistry;
    private final OAuthStateService stateService;
    private final SettingService settingService;
    private final RequestAuthority authority;
    private final ApplicationEventPublisher events;
    private final String publicBaseUrl;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthController(
            OAuthConfigRegistry configRegistry,
            OAuthStateService stateService,
            SettingService settingService,
            RequestAuthority authority,
            ApplicationEventPublisher events,
            @Value("${vance.web.publicBaseUrl:http://localhost:18080}") String publicBaseUrl) {
        this.configRegistry = configRegistry;
        this.stateService = stateService;
        this.settingService = settingService;
        this.authority = authority;
        this.events = events;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    // ──────────────────── Providers list ────────────────────

    /**
     * Lists every provider the tenant has configured, with a
     * {@code connected} flag for the calling user. Drives the
     * Web-UI's "Connected Accounts" page.
     */
    @GetMapping("/providers")
    public List<OAuthProviderListEntry> listProviders(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        List<OAuthProviderListEntry> out = new ArrayList<>();
        for (ResolvedOAuthProvider rp : configRegistry.list(tenant)) {
            out.add(OAuthProviderListEntry.builder()
                    .providerId(rp.providerId())
                    .typeId(rp.typeId())
                    .connected(isConnected(tenant, userId, rp.providerId()))
                    .build());
        }
        out.sort(Comparator.comparing(OAuthProviderListEntry::getProviderId));
        return out;
    }

    // ──────────────────── Init: redirect to provider ────────────────────

    /**
     * Starts the Authorization-Code flow. Mints a state token bound to
     * the calling tenant+user, asks the provider for the authorize URL,
     * and 302-redirects the browser to it. The browser comes back to
     * the callback endpoint with {@code code}+{@code state}.
     */
    @GetMapping("/{providerId}/init")
    public ResponseEntity<Void> init(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        ResolvedOAuthProvider provider = configRegistry.resolve(tenant, providerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No OAuth provider '" + providerId + "' for tenant '" + tenant + "'"));

        if (provider.config().clientSecret().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "OAuth provider '" + providerId
                            + "' has no client secret — set tenant setting '"
                            + OAuthProviderLoader.clientSecretKey(providerId) + "'");
        }

        String codeVerifier = GenericOAuth2Provider.isPkceEnabled(provider.config())
                ? mintPkceVerifier()
                : null;
        String state = stateService.start(
                tenant, userId, providerId, returnTo, codeVerifier);
        String redirectUri = redirectUriFor(tenant, providerId);
        OAuthInitContext ctx = new OAuthInitContext(
                tenant, userId, state, redirectUri, returnTo, codeVerifier);

        URI authUri;
        try {
            authUri = provider.provider().buildAuthorizeUri(provider.config(), ctx);
        } catch (RuntimeException ex) {
            log.warn("OAuth init: provider '{}' failed to build authorize URI: {}",
                    providerId, ex.toString());
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "OAuth provider '" + providerId + "' failed to start the flow", ex);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(authUri).build();
    }

    // ──────────────────── Callback: consume code, persist tokens ─────────

    /**
     * Provider-redirected entrypoint. Validates the state, exchanges
     * the {@code code} for tokens, persists them as user-settings,
     * and 302-redirects the browser to the original {@code returnTo}
     * (or to {@code /} if absent).
     */
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        Optional<OAuthStateService.Consumed> consumed =
                stateService.consume(state, tenant, userId);
        if (consumed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid or expired OAuth state");
        }
        // The state binds to a specific providerId; reject when the
        // URL path tries to switch provider mid-flow (mix-up attack).
        if (!providerId.equals(consumed.get().providerId())) {
            log.warn("OAuth callback: providerId mismatch — url='{}' state='{}'",
                    providerId, consumed.get().providerId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OAuth state does not match providerId in URL");
        }

        ResolvedOAuthProvider provider = configRegistry.resolve(tenant, providerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No OAuth provider '" + providerId + "' for tenant '" + tenant + "'"));

        String redirectUri = redirectUriFor(tenant, providerId);
        OAuthInitContext ctx = new OAuthInitContext(
                tenant, userId, state, redirectUri,
                consumed.get().returnTo(),
                consumed.get().codeVerifier());

        OAuthTokenSet tokens;
        try {
            tokens = provider.provider().exchangeCode(provider.config(), code, ctx);
        } catch (OAuthFlowException ex) {
            log.warn("OAuth callback: provider '{}' rejected code exchange: {}",
                    providerId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OAuth provider '" + providerId + "' rejected token exchange: "
                            + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            log.warn("OAuth callback: provider '{}' threw during exchange: {}",
                    providerId, ex.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "OAuth provider '" + providerId + "' failed during token exchange", ex);
        }

        persistTokens(tenant, userId, providerId, tokens);
        log.info("OAuth connected: tenant='{}' user='{}' provider='{}'",
                tenant, userId, providerId);

        URI returnUri = resolveReturnUri(consumed.get().returnTo());
        return ResponseEntity.status(HttpStatus.FOUND).location(returnUri).build();
    }

    // ──────────────────── Disconnect ────────────────────

    /**
     * Removes every per-user setting for {@code (tenant, user, providerId)}.
     * Tokens at the provider end stay until revoked there; this endpoint
     * only erases the local copy so the next tool call fails fast and
     * the Web-UI shows the provider as disconnected.
     */
    @DeleteMapping("/{providerId}/connection")
    public ResponseEntity<Void> disconnect(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);
        String userId = authority.contextOf(request).subjectId();

        String userRef = HomeBootstrapService.hubProjectName(userId);
        // Wipe every key under oauth.<providerId>.* — covers the well-
        // known tokens (access/refresh/expires_at/scopes/extra) AND any
        // flat-extra projections the connect flow may have written
        // (cloud_id, site_url, …). Doing this by prefix means future
        // extras don't quietly survive a disconnect.
        String prefix = USER_TOKEN_KEY_PREFIX + providerId + ".";
        long removed = settingService.deleteByPrefix(
                tenant, SettingService.SCOPE_PROJECT, userRef, prefix);

        // Notify cached connection pools (MCP, REST) so any open
        // session bound to the freshly-wiped tokens drops immediately.
        // A new tool call afterwards rebuilds the connection from a
        // clean slate and surfaces a proper "user must reconnect" path.
        events.publishEvent(new OAuthDisconnectedEvent(tenant, userId, providerId));

        log.info("OAuth disconnected: tenant='{}' user='{}' provider='{}' settings_removed={}",
                tenant, userId, providerId, removed);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────── Internals ────────────────────

    private boolean isConnected(String tenant, String userId, String providerId) {
        return settingService.getDecryptedUserPassword(tenant, userId,
                USER_TOKEN_KEY_PREFIX + providerId + KEY_ACCESS_TOKEN) != null;
    }

    private void persistTokens(String tenant, String userId, String providerId, OAuthTokenSet tokens) {
        String userRef = HomeBootstrapService.hubProjectName(userId);
        String base = USER_TOKEN_KEY_PREFIX + providerId;

        settingService.setEncryptedPassword(tenant, SettingService.SCOPE_PROJECT, userRef,
                base + KEY_ACCESS_TOKEN, tokens.accessToken());

        if (tokens.refreshToken() != null) {
            settingService.setEncryptedPassword(tenant, SettingService.SCOPE_PROJECT, userRef,
                    base + KEY_REFRESH_TOKEN, tokens.refreshToken());
        } else {
            // Drop a stale refresh token from a previous connect when
            // the new flow doesn't issue one (provider downgrade /
            // scope change).
            settingService.delete(tenant, SettingService.SCOPE_PROJECT, userRef,
                    base + KEY_REFRESH_TOKEN);
        }

        if (tokens.expiresAt() != null) {
            settingService.set(tenant, SettingService.SCOPE_PROJECT, userRef,
                    base + KEY_EXPIRES_AT, tokens.expiresAt().toString(),
                    SettingType.STRING, null);
        } else {
            settingService.delete(tenant, SettingService.SCOPE_PROJECT, userRef,
                    base + KEY_EXPIRES_AT);
        }

        if (!tokens.extraClaims().isEmpty()) {
            String scopesValue = tokens.extraClaims().getOrDefault("scope", "");
            if (!scopesValue.isEmpty()) {
                settingService.set(tenant, SettingService.SCOPE_PROJECT, userRef,
                        base + KEY_SCOPES, scopesValue, SettingType.STRING, null);
            }
            // Persist the remaining claims as a JSON blob — provider-
            // specific metadata (team_id, cloud_id, token_type, …).
            Map<String, String> nonScope = new LinkedHashMap<>(tokens.extraClaims());
            nonScope.remove("scope");
            if (!nonScope.isEmpty()) {
                // The JSON blob is STRING (unencrypted) — keep secret-bearing
                // claims (bot_access_token, id_token, …) out of it; they are
                // stored encrypted via the flat projection below (F4).
                Map<String, String> nonSecret = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : nonScope.entrySet()) {
                    if (!OAuthTokenSet.isSecretClaimKey(e.getKey())) {
                        nonSecret.put(e.getKey(), e.getValue());
                    }
                }
                if (nonSecret.isEmpty()) {
                    settingService.delete(tenant, SettingService.SCOPE_PROJECT, userRef,
                            base + KEY_EXTRA);
                } else {
                    try {
                        settingService.set(tenant, SettingService.SCOPE_PROJECT, userRef,
                                base + KEY_EXTRA,
                                json.writeValueAsString(nonSecret),
                                SettingType.STRING, null);
                    } catch (RuntimeException ex) {
                        log.warn("OAuth: failed to serialise extra claims for '{}': {}",
                                providerId, ex.toString());
                    }
                }
                // Also publish each scalar extra as a flat user-setting so
                // tool templates can reference {{secret:user:oauth.<provider>.<extraKey>}}
                // without parsing the JSON blob (cloud_id, site_url, …).
                // Secret-bearing claims go in as PASSWORD (encrypted at rest);
                // the secret resolver reads PASSWORD first, STRING as fallback.
                // JSON-valued non-secret strings (e.g. accessible_resources)
                // stay in the blob — skip them here to avoid bloating the cache.
                for (Map.Entry<String, String> e : nonScope.entrySet()) {
                    String v = e.getValue();
                    if (v == null || v.isEmpty()) continue;
                    if (OAuthTokenSet.isSecretClaimKey(e.getKey())) {
                        settingService.setEncryptedPassword(tenant, SettingService.SCOPE_PROJECT,
                                userRef, base + "." + e.getKey(), v);
                    } else if (!looksLikeJsonContainer(v)) {
                        settingService.set(tenant, SettingService.SCOPE_PROJECT, userRef,
                                base + "." + e.getKey(), v, SettingType.STRING, null);
                    }
                }
            }
        }
    }

    /** Crude JSON-array/object detector — keeps blobs out of the flat-extra projection. */
    private static boolean looksLikeJsonContainer(String s) {
        if (s == null || s.length() < 2) return false;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    private String redirectUriFor(String tenant, String providerId) {
        return UriComponentsBuilder.fromUriString(publicBaseUrl)
                .pathSegment("brain", tenant, "oauth", providerId, "callback")
                .build()
                .toUriString();
    }

    private static URI resolveReturnUri(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return URI.create("/");
        }
        // Only relative paths are accepted — never let a caller send the
        // browser to a foreign origin. A leading slash forces same-origin
        // navigation in the browser.
        if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return URI.create(returnTo);
        }
        return URI.create("/");
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Mint a PKCE code-verifier (RFC 7636 §4.1) — 32 bytes of
     * {@link SecureRandom} entropy, Base64URL-encoded without padding.
     * Result is 43 characters of {@code [A-Za-z0-9-_]}, well within the
     * 43-128 range the spec allows.
     */
    private String mintPkceVerifier() {
        byte[] bytes = new byte[PKCE_VERIFIER_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
