package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standard OAuth 2.0 Authorization-Code-Grant flow — RFC 6749 §4.1.
 * Handles the providers that don't ship an OpenID-Connect Discovery
 * document and expect endpoints in the YAML config (GitHub classic
 * OAuth, Atlassian 3LO, generic SaaS). The {@link OidcProvider}
 * subclass plugs Discovery in front of {@link #resolveAuthorizeUrl}/
 * {@link #resolveTokenUrl}.
 *
 * <p>Client credentials are sent in the form body — most providers
 * accept either Basic auth or body; body is the lower-common-denominator
 * (RFC 6749 §2.3.1 also calls it out as supported). Quirk-subclasses
 * (Slack v2, Atlassian) may override the token-response parsing.
 */
@Component
@Slf4j
public class GenericOAuth2Provider implements OAuthProvider {

    /** Bean-type identifier. */
    public static final String TYPE_ID = "generic-oauth2";

    /** Default HTTP timeout for outbound token requests. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    protected final PackHttpClient httpClient;
    protected final ObjectMapper json;

    public GenericOAuth2Provider() {
        this(new PackHttpClient());
    }

    GenericOAuth2Provider(PackHttpClient httpClient) {
        this.httpClient = httpClient;
        this.json = JsonMapper.builder().build();
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public URI buildAuthorizeUri(OAuthProviderConfig cfg, OAuthInitContext ctx) {
        String base = resolveAuthorizeUrl(cfg);
        if (base == null || base.isBlank()) {
            throw new OAuthFlowException(cfg.providerId(),
                    "no authorize URL configured for '" + cfg.providerId() + "'");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", cfg.clientId());
        params.put("redirect_uri", ctx.redirectUri());
        params.put("state", ctx.state());
        if (!cfg.scopes().isEmpty()) {
            params.put("scope", String.join(" ", cfg.scopes()));
        }
        decorateAuthorizeParams(cfg, ctx, params);
        return URI.create(base + (base.contains("?") ? "&" : "?") + formEncode(params));
    }

    @Override
    public OAuthTokenSet exchangeCode(
            OAuthProviderConfig cfg, String code, OAuthInitContext ctx) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", ctx.redirectUri());
        form.put("client_id", cfg.clientId());
        form.put("client_secret", cfg.clientSecret());
        return postForm(cfg, form);
    }

    @Override
    public OAuthTokenSet refresh(OAuthProviderConfig cfg, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", cfg.clientId());
        form.put("client_secret", cfg.clientSecret());
        return postForm(cfg, form);
    }

    // ──────────────────── Subclass-overridable hooks ────────────────────

    /**
     * Authorize URL for this config. The base implementation uses
     * {@code cfg.authorizeUrl()}; the {@link OidcProvider} subclass
     * pulls it from the discovery document.
     */
    protected @Nullable String resolveAuthorizeUrl(OAuthProviderConfig cfg) {
        return cfg.authorizeUrl();
    }

    /** Token URL for this config — same Discovery override pattern as authorize. */
    protected @Nullable String resolveTokenUrl(OAuthProviderConfig cfg) {
        return cfg.tokenUrl();
    }

    /**
     * Adds provider-specific authorize-URL parameters (Google's
     * {@code access_type=offline}, Slack's user-vs-bot toggle, …).
     * Default is no-op.
     */
    protected void decorateAuthorizeParams(
            OAuthProviderConfig cfg, OAuthInitContext ctx, Map<String, String> params) {
        // No-op for the generic flavour.
    }

    /**
     * Parse a successful token-response JSON body into an
     * {@link OAuthTokenSet}. Quirk subclasses (Slack v2 returns
     * {@code authed_user.access_token}) override this.
     */
    protected OAuthTokenSet parseTokenResponse(JsonNode root, String providerId) {
        String accessToken = stringOrNull(root, "access_token");
        if (accessToken == null) {
            throw new OAuthFlowException(providerId,
                    "token response missing 'access_token'");
        }
        String refreshToken = stringOrNull(root, "refresh_token");
        Instant expiresAt = null;
        if (root.has("expires_in") && root.get("expires_in").isNumber()) {
            long seconds = root.get("expires_in").asLong();
            expiresAt = Instant.now().plusSeconds(seconds);
        }
        Map<String, String> extra = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = root.propertyNames().iterator(); it.hasNext(); ) {
            String name = it.next();
            if ("access_token".equals(name) || "refresh_token".equals(name)
                    || "expires_in".equals(name)) {
                continue;
            }
            JsonNode value = root.get(name);
            if (value == null || value.isNull()) continue;
            extra.put(name, value.isValueNode() ? value.asString() : value.toString());
        }
        return new OAuthTokenSet(accessToken, refreshToken, expiresAt, extra);
    }

    // ──────────────────── HTTP layer ────────────────────

    private OAuthTokenSet postForm(OAuthProviderConfig cfg, Map<String, String> form) {
        String url = resolveTokenUrl(cfg);
        if (url == null || url.isBlank()) {
            throw new OAuthFlowException(cfg.providerId(),
                    "no token URL configured for '" + cfg.providerId() + "'");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.client(PackHttpClient.TlsConfig.DEFAULT)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthFlowException(cfg.providerId(),
                    "token request interrupted", e);
        } catch (java.io.IOException e) {
            throw new OAuthFlowException(cfg.providerId(),
                    "token request failed: " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            String snippet = body == null || body.isEmpty() ? ""
                    : (body.length() > 200 ? body.substring(0, 200) + "…" : body);
            throw new OAuthFlowException(cfg.providerId(),
                    "token endpoint returned HTTP " + response.statusCode()
                            + " — " + snippet);
        }

        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new OAuthFlowException(cfg.providerId(),
                    "token response is not valid JSON: " + e.getMessage(), e);
        }
        // Some providers tunnel errors in a 200-OK body — RFC 6749 §5.2.
        if (root.has("error")) {
            String error = stringOrNull(root, "error");
            String description = stringOrNull(root, "error_description");
            throw new OAuthFlowException(cfg.providerId(),
                    "token response carries OAuth error '"
                            + error + "': "
                            + (description == null ? "(no description)" : description));
        }
        return parseTokenResponse(root, cfg.providerId());
    }

    // ──────────────────── Helpers ────────────────────

    private static @Nullable String stringOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asString();
        return s.isEmpty() ? null : s;
    }

    private static String formEncode(Map<String, String> form) {
        List<String> parts = new ArrayList<>(form.size());
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            parts.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }
}
