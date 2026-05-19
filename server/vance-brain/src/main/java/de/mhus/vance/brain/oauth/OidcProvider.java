package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * OpenID-Connect provider — reads the standard
 * {@code .well-known/openid-configuration} document to discover the
 * authorize / token endpoints, then delegates the actual flow to
 * {@link GenericOAuth2Provider}. Works out of the box for Keycloak,
 * Okta, Auth0, Authentik, Microsoft Entra and Google (any
 * OIDC-compliant IdP).
 *
 * <p>{@code id_token} validation (JWKS lookup + signature verify) is
 * a v2 concern — Vance uses the access token against the IdP's API,
 * we don't act on identity claims from the {@code id_token} itself.
 *
 * <p>Discovery responses are cached by URL for the lifetime of the
 * JVM. Tenants that change their {@code discoveryUrl} get a fresh
 * cache entry on the next config edit; the old one stays until JVM
 * restart (small memory leak, no correctness issue).
 */
@Component
@Slf4j
public class OidcProvider extends GenericOAuth2Provider {

    /** Bean-type identifier. */
    public static final String TYPE_ID = "oidc";

    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(15);

    private final ConcurrentMap<String, DiscoveryDoc> discoveryCache = new ConcurrentHashMap<>();

    public OidcProvider() {
        super();
    }

    OidcProvider(PackHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    protected @Nullable String resolveAuthorizeUrl(OAuthProviderConfig cfg) {
        // Explicit override in YAML wins — useful when the discovery
        // doc points at the wrong host (e.g. internal cluster URL vs.
        // browser-reachable URL).
        if (cfg.authorizeUrl() != null) return cfg.authorizeUrl();
        DiscoveryDoc doc = discover(cfg);
        return doc == null ? null : doc.authorizeUrl();
    }

    @Override
    protected @Nullable String resolveTokenUrl(OAuthProviderConfig cfg) {
        if (cfg.tokenUrl() != null) return cfg.tokenUrl();
        DiscoveryDoc doc = discover(cfg);
        return doc == null ? null : doc.tokenUrl();
    }

    /**
     * Clear the cached discovery for a provider — used by the admin
     * controller right after a provider's {@code discoveryUrl} changes.
     * No-op when nothing is cached. Public so the
     * {@code OAuthConfigRegistry.refreshOne} path can also invalidate.
     */
    public void invalidateDiscovery(String discoveryUrl) {
        if (discoveryUrl != null) {
            discoveryCache.remove(discoveryUrl);
        }
    }

    private @Nullable DiscoveryDoc discover(OAuthProviderConfig cfg) {
        String url = cfg.discoveryUrl();
        if (url == null || url.isBlank()) return null;
        return discoveryCache.computeIfAbsent(url,
                u -> fetchDiscovery(u, cfg.providerId()));
    }

    private DiscoveryDoc fetchDiscovery(String url, String providerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DISCOVERY_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.client(PackHttpClient.TlsConfig.DEFAULT)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthFlowException(providerId,
                    "discovery request interrupted: " + url, e);
        } catch (java.io.IOException e) {
            throw new OAuthFlowException(providerId,
                    "discovery request failed: " + url + " — " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OAuthFlowException(providerId,
                    "discovery URL '" + url + "' returned HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new OAuthFlowException(providerId,
                    "discovery URL '" + url + "' did not return valid JSON: " + e.getMessage(), e);
        }
        String authorizeUrl = stringOrNull(root, "authorization_endpoint");
        String tokenUrl = stringOrNull(root, "token_endpoint");
        if (authorizeUrl == null || tokenUrl == null) {
            throw new OAuthFlowException(providerId,
                    "discovery doc at '" + url
                            + "' is missing 'authorization_endpoint' or 'token_endpoint'");
        }
        log.info("OidcProvider: discovered endpoints for '{}' from '{}'", providerId, url);
        return new DiscoveryDoc(authorizeUrl, tokenUrl);
    }

    private static @Nullable String stringOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asString();
        return s.isEmpty() ? null : s;
    }

    /** Cached subset of the OIDC discovery document we care about. */
    record DiscoveryDoc(String authorizeUrl, String tokenUrl) {
    }
}
