package de.mhus.vance.brain.vault;

import de.mhus.vance.shared.vault.VaultBinding;
import de.mhus.vance.shared.vault.VaultException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbound HTTP client for one Infisical instance. Sole speaker of the
 * Infisical REST API — {@link InfisicalVaultProvider} delegates here and holds
 * no transport logic itself.
 *
 * <p>Auth is Machine-Identity Universal Auth: {@code POST /api/v1/auth/
 * universal-auth/login} exchanges {@code (clientId, clientSecret)} for a
 * short-lived access token, cached per {@code (baseUrl, clientId)} until it
 * nears expiry. Secrets use the v4 raw API ({@code /api/v4/secrets/{name}} with
 * {@code projectId}/{@code environment}/{@code secretPath}); the decrypted value
 * lives at {@code secret.secretValue}.
 *
 * <p>Stateless w.r.t. scope: connection details arrive on every call via the
 * {@link VaultBinding}, so one bean serves every tenant/project/user. Only the
 * access-token cache is retained, keyed by the binding's endpoint+identity.
 */
@Component
@Slf4j
public class InfisicalClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** Refresh the token this long before its declared expiry. */
    private static final long TOKEN_REFRESH_MARGIN_MS = 30_000;
    private static final String LOGIN_PATH = "/api/v1/auth/universal-auth/login";
    private static final String SECRETS_PATH = "/api/v4/secrets/";

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final LongSupplier clock;

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public InfisicalClient(ObjectMapper objectMapper) {
        this(objectMapper,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                System::currentTimeMillis);
    }

    InfisicalClient(ObjectMapper objectMapper, HttpClient http, LongSupplier clock) {
        this.objectMapper = objectMapper;
        this.http = http;
        this.clock = clock;
    }

    /**
     * @return the secret value, or {@code null} if Infisical has no such key
     * @throws VaultException on missing binding fields, auth failure, or any
     *         non-2xx/404 response
     */
    @Nullable String readSecret(VaultBinding binding, String secretName) {
        Endpoint ep = endpoint(binding);
        String url = ep.base + SECRETS_PATH + encPath(secretName)
                + "?projectId=" + enc(ep.projectId)
                + "&environment=" + enc(ep.environment)
                + "&secretPath=" + enc(ep.secretPath)
                + "&viewSecretValue=true";
        HttpResponse<String> resp = sendAuthorized("GET", url, null, ep);
        if (resp.statusCode() == 404) {
            return null;
        }
        if (!is2xx(resp)) {
            throw httpError("read secret '" + secretName + "'", resp);
        }
        JsonNode value = parse(resp.body()).path("secret").path("secretValue");
        return (value.isMissingNode() || value.isNull()) ? null : value.asText();
    }

    /**
     * Create-or-update {@code secretName} = {@code value}. Tries update first,
     * falls back to create when the secret does not exist yet.
     *
     * @throws VaultException on missing binding fields, auth failure, or a
     *         non-2xx response (a read-only machine identity fails here)
     */
    void writeSecret(VaultBinding binding, String secretName, String value) {
        Endpoint ep = endpoint(binding);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", ep.projectId);
        body.put("environment", ep.environment);
        body.put("secretPath", ep.secretPath);
        body.put("secretValue", value);
        String json = serialize(body);
        String url = ep.base + SECRETS_PATH + encPath(secretName);

        HttpResponse<String> update = sendAuthorized("PATCH", url, json, ep);
        if (update.statusCode() == 404) {
            HttpResponse<String> create = sendAuthorized("POST", url, json, ep);
            if (!is2xx(create)) {
                throw httpError("create secret '" + secretName + "'", create);
            }
            return;
        }
        if (!is2xx(update)) {
            throw httpError("update secret '" + secretName + "'", update);
        }
    }

    // ──────────────────── transport ────────────────────

    private HttpResponse<String> sendAuthorized(
            String method, String url, @Nullable String jsonBody, Endpoint ep) {
        boolean usedCachedToken = tokenCache.containsKey(cacheKey(ep));
        HttpResponse<String> resp = doSend(method, url, jsonBody, accessToken(ep, false));
        // Retry only when a *cached* token was used — it may have gone stale
        // mid-cache. A 401 on a token we just logged in for means the identity
        // itself is rejected; re-logging in would only repeat the failure (and
        // double the latency of a doomed call).
        if (resp.statusCode() == 401 && usedCachedToken) {
            resp = doSend(method, url, jsonBody, accessToken(ep, true));
        }
        return resp;
    }

    private HttpResponse<String> doSend(
            String method, String url, @Nullable String jsonBody, String token) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if (jsonBody != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return send(rb.build());
    }

    // ──────────────────── auth ────────────────────

    private static String cacheKey(Endpoint ep) {
        return ep.base + "|" + ep.clientId;
    }

    private String accessToken(Endpoint ep, boolean forceRefresh) {
        String cacheKey = cacheKey(ep);
        if (forceRefresh) {
            tokenCache.remove(cacheKey);
        } else {
            CachedToken cached = tokenCache.get(cacheKey);
            if (cached != null && cached.expiresAtMillis > clock.getAsLong() + TOKEN_REFRESH_MARGIN_MS) {
                return cached.accessToken;
            }
        }
        CachedToken fresh = login(ep);
        tokenCache.put(cacheKey, fresh);
        return fresh.accessToken;
    }

    private CachedToken login(Endpoint ep) {
        if (ep.clientSecret == null || ep.clientSecret.isBlank()) {
            throw new VaultException(
                    "Infisical vault at " + ep.base + " has no machine-identity secret configured"
                            + " (set the PASSWORD setting 'vault.clientSecret')");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", ep.clientId);
        body.put("clientSecret", ep.clientSecret);
        HttpRequest req = HttpRequest.newBuilder(URI.create(ep.base + LOGIN_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (!is2xx(resp)) {
            throw httpError("universal-auth login", resp);
        }
        JsonNode node = parse(resp.body());
        JsonNode tokenNode = node.path("accessToken");
        if (tokenNode.isMissingNode() || tokenNode.isNull()) {
            throw new VaultException("Infisical login response missing 'accessToken'");
        }
        long expiresInSec = node.path("expiresIn").asLong();
        if (expiresInSec <= 0) {
            // A missing/zero TTL would defeat the cache (immediate re-login on
            // every call). Fall back to a short conservative lifetime.
            expiresInSec = 60;
        }
        long expiresAt = clock.getAsLong() + expiresInSec * 1000L;
        return new CachedToken(tokenNode.asText(), expiresAt);
    }

    // ──────────────────── helpers ────────────────────

    private static Endpoint endpoint(VaultBinding binding) {
        Map<String, String> cfg = binding.config();
        return new Endpoint(
                stripTrailingSlash(binding.baseUrl()),
                required(cfg.get("clientId"), "vault.clientId"),
                binding.secret(),
                required(cfg.get("project"), "vault.project"),
                required(cfg.get("environment"), "vault.environment"),
                blankToRoot(cfg.get("path")));
    }

    private static String required(@Nullable String value, String settingKey) {
        if (value == null || value.isBlank()) {
            throw new VaultException("Infisical vault is missing required setting '" + settingKey + "'");
        }
        return value;
    }

    private static String blankToRoot(@Nullable String path) {
        return (path == null || path.isBlank()) ? "/" : path;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("Infisical request interrupted", e);
        } catch (IOException e) {
            throw new VaultException("Infisical HTTP error: " + e.getMessage(), e);
        }
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new VaultException("Failed to serialise Infisical request body", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new VaultException("Failed to parse Infisical response body", e);
        }
    }

    private static boolean is2xx(HttpResponse<String> resp) {
        return resp.statusCode() / 100 == 2;
    }

    private static VaultException httpError(String op, HttpResponse<String> resp) {
        return new VaultException(
                "Infisical " + op + " failed: HTTP " + resp.statusCode() + " — " + snippet(resp.body()));
    }

    private static String snippet(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Path-segment encoding: {@link URLEncoder} emits {@code +} for spaces, invalid in a path. */
    private static String encPath(String s) {
        return enc(s).replace("+", "%20");
    }

    private record Endpoint(
            String base,
            String clientId,
            @Nullable String clientSecret,
            String projectId,
            String environment,
            String secretPath) {}

    private record CachedToken(String accessToken, long expiresAtMillis) {}
}
