package de.mhus.vance.brain.sso;

import de.mhus.vance.shared.sso.SsoConfig;
import de.mhus.vance.toolpack.core.PackHttpClient;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The only code that talks to the identity provider: discovery, code
 * exchange, signing keys.
 *
 * <p>Discovery and key sets are cached. Keys follow the provider's
 * {@code Cache-Control: max-age}; an unknown {@code kid} triggers one early
 * re-fetch (key rotation), at most once a minute per key set so a stream of
 * forged tokens cannot turn us into a request amplifier.
 */
@Service
@Slf4j
public class SsoOidcClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DISCOVERY_TTL = Duration.ofHours(1);
    private static final Duration DEFAULT_KEYS_TTL = Duration.ofHours(1);
    private static final Duration MIN_KEYS_REFETCH = Duration.ofMinutes(1);
    private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final ConcurrentMap<String, Cached<Discovery>> discovery = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cached<JwkSet>> keySets = new ConcurrentHashMap<>();

    public SsoOidcClient() {
        this(new PackHttpClient(TIMEOUT).client(PackHttpClient.TlsConfig.DEFAULT), Clock.systemUTC());
    }

    SsoOidcClient(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /** Endpoints of a provider. */
    public record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

    /** The provider could not be reached or answered nonsense. */
    public static class IdentityProviderException extends Exception {
        public IdentityProviderException(String message) {
            super(message);
        }

        public IdentityProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Cached<T>(T value, Instant expiresAt, Instant fetchedAt) {}

    public Discovery discover(SsoConfig.Provider provider) throws IdentityProviderException {
        Cached<Discovery> hit = discovery.get(provider.discoveryUrl());
        if (hit != null && hit.expiresAt().isAfter(clock.instant())) {
            return hit.value();
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(provider.discoveryUrl()))
                .timeout(TIMEOUT)
                .GET()
                .build());
        JsonNode doc = parse(response.body(), "discovery document");
        String issuer = text(doc, "issuer");
        // Google's discovery says https://accounts.google.com; the config may
        // only name what discovery confirms.
        if (!provider.issuer().equals(issuer)) {
            throw new IdentityProviderException(
                    "discovery issuer '" + issuer + "' does not match configured '" + provider.issuer() + "'");
        }
        Discovery d =
                new Discovery(text(doc, "authorization_endpoint"), text(doc, "token_endpoint"), text(doc, "jwks_uri"));
        Instant now = clock.instant();
        discovery.put(provider.discoveryUrl(), new Cached<>(d, now.plus(DISCOVERY_TTL), now));
        return d;
    }

    /**
     * Exchanges an authorization code (with PKCE verifier) for the ID token.
     */
    public String exchangeCode(
            Discovery endpoints,
            String clientId,
            String clientSecret,
            String code,
            String redirectUri,
            String codeVerifier)
            throws IdentityProviderException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("code_verifier", codeVerifier);
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoints.tokenEndpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        JsonNode doc = parse(response.body(), "token response");
        JsonNode idToken = doc.get("id_token");
        if (idToken == null || !idToken.isString() || idToken.asString().isBlank()) {
            throw new IdentityProviderException("token response carries no id_token");
        }
        return idToken.asString();
    }

    /**
     * Signing key {@code kid} of the provider, or {@code null} if it does not
     * publish one by that id.
     */
    public @Nullable Key publicKey(Discovery endpoints, String kid) throws IdentityProviderException {
        Instant now = clock.instant();
        Cached<JwkSet> cached = keySets.get(endpoints.jwksUri());
        if (cached == null || cached.expiresAt().isBefore(now)) {
            cached = fetchKeys(endpoints.jwksUri());
        }
        Key key = find(cached.value(), kid);
        if (key == null && cached.fetchedAt().plus(MIN_KEYS_REFETCH).isBefore(now)) {
            key = find(fetchKeys(endpoints.jwksUri()).value(), kid);
        }
        return key;
    }

    private Cached<JwkSet> fetchKeys(String jwksUri) throws IdentityProviderException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(TIMEOUT)
                .GET()
                .build());
        JwkSet set;
        try {
            set = Jwks.setParser().build().parse(response.body());
        } catch (RuntimeException e) {
            throw new IdentityProviderException("key set at " + jwksUri + " does not parse", e);
        }
        Instant now = clock.instant();
        Duration ttl = response.headers()
                .firstValue("Cache-Control")
                .map(MAX_AGE::matcher)
                .filter(Matcher::find)
                .map(m -> Duration.ofSeconds(Long.parseLong(m.group(1))))
                .orElse(DEFAULT_KEYS_TTL);
        Cached<JwkSet> fresh = new Cached<>(set, now.plus(ttl), now);
        keySets.put(jwksUri, fresh);
        return fresh;
    }

    private static @Nullable Key find(JwkSet set, String kid) {
        for (Jwk<?> jwk : set.getKeys()) {
            if (kid.equals(jwk.getId())) {
                return jwk.toKey();
            }
        }
        return null;
    }

    private HttpResponse<String> send(HttpRequest request) throws IdentityProviderException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("request to " + request.uri() + " interrupted", e);
        } catch (IOException e) {
            throw new IdentityProviderException("request to " + request.uri() + " failed: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            // The body of an error answer (e.g. invalid_grant) goes to the log
            // only through the caller's message — no secrets are in it.
            throw new IdentityProviderException(
                    request.uri() + " answered HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response;
    }

    private JsonNode parse(String body, String what) throws IdentityProviderException {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new IdentityProviderException(what + " is not JSON", e);
        }
    }

    private static String text(JsonNode doc, String field) throws IdentityProviderException {
        JsonNode node = doc.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new IdentityProviderException("missing '" + field + "'");
        }
        return node.asString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(@Nullable String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
