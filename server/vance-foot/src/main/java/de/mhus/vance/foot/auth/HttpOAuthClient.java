package de.mhus.vance.foot.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Default {@link OAuthClient} — JDK {@link HttpClient}, form posts, JSON answers. */
@Component
@Slf4j
public class HttpOAuthClient implements OAuthClient {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    public Endpoints discover(String httpBase, String tenant) throws Exception {
        String url = trimSlash(httpBase) + "/brain/" + tenant + "/.well-known/openid-configuration";
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OAuth discovery failed: HTTP " + response.statusCode()
                    + " — is the OAuth server enabled on this brain (vance.web.public-base-url)?");
        }
        JsonNode doc = json.readTree(response.body());
        String issuer = text(doc, "issuer");
        String authorize = text(doc, "authorization_endpoint");
        String token = text(doc, "token_endpoint");
        if (issuer == null || authorize == null || token == null) {
            throw new IllegalStateException("OAuth discovery document is incomplete");
        }
        JsonNode methods = doc.get("code_challenge_methods_supported");
        boolean s256 = false;
        if (methods != null && methods.isArray()) {
            for (JsonNode m : methods) {
                s256 |= "S256".equals(m.asString());
            }
        }
        if (!s256) {
            throw new IllegalStateException("authorization server does not support PKCE S256");
        }
        return new Endpoints(issuer, authorize, token, text(doc, "revocation_endpoint"));
    }

    @Override
    public Tokens exchangeCode(
            Endpoints endpoints, String clientId, String code, String redirectUri, String codeVerifier)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", codeVerifier);
        return tokens(post(endpoints.tokenEndpoint(), form));
    }

    @Override
    public Tokens refresh(String tokenEndpoint, String clientId, String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        return tokens(post(tokenEndpoint, form));
    }

    @Override
    public void revoke(String revocationEndpoint, String clientId, String token) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("client_id", clientId);
        try {
            post(revocationEndpoint, form);
        } catch (Exception e) {
            log.debug("OAuth revoke failed (ignored): {}", e.toString());
        }
    }

    private HttpResponse<String> post(String url, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .header("accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Tokens tokens(HttpResponse<String> response) throws Exception {
        JsonNode doc = response.body().isBlank() ? null : json.readTree(response.body());
        if (response.statusCode() != 200) {
            String error = doc == null ? null : text(doc, "error");
            String description = doc == null ? null : text(doc, "error_description");
            String message = "OAuth token endpoint: " + (error == null ? "HTTP " + response.statusCode() : error)
                    + (description == null ? "" : " — " + description);
            if (response.statusCode() == 400 || response.statusCode() == 401) {
                throw new OAuthRefusedException(message);
            }
            throw new IllegalStateException(message);
        }
        String access = doc == null ? null : text(doc, "access_token");
        if (access == null) {
            throw new IllegalStateException("OAuth token endpoint answered without access_token");
        }
        JsonNode expires = doc.get("expires_in");
        long expiresIn = expires != null && expires.isNumber() ? expires.asLong() : 3600L;
        return new Tokens(access, expiresIn, text(doc, "refresh_token"));
    }

    private static @Nullable String text(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
