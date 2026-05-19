package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.toolpack.core.PackHttpClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests for {@link GenericOAuth2Provider} — runs the
 * full HTTP roundtrip against a locally-spun {@link HttpServer}.
 * Covers authorize-URL construction, code-exchange happy + error
 * paths, and refresh-token rotation.
 */
class GenericOAuth2ProviderTest {

    private HttpServer server;
    private int port;
    private AtomicReference<RecordedRequest> lastRequest;
    /** Switchable token-endpoint response per test. */
    private AtomicReference<TokenResponse> nextResponse;
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        lastRequest = new AtomicReference<>();
        nextResponse = new AtomicReference<>();
        callCount.set(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/token", this::handleToken);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // ─────── buildAuthorizeUri ───────

    @Test
    void authorize_uri_carries_required_params() {
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("https://provider.example/authorize",
                "http://localhost/token", List.of("openid", "email"));
        OAuthInitContext ctx = ctx("STATE-XYZ",
                "https://vance.example.com/brain/acme/oauth/keycloak/callback");

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString())
                .startsWith("https://provider.example/authorize?")
                .contains("response_type=code")
                .contains("client_id=my-app")
                .contains("state=STATE-XYZ")
                .contains("scope=openid+email")
                .contains("redirect_uri=https%3A%2F%2Fvance.example.com%2Fbrain%2Facme%2Foauth%2Fkeycloak%2Fcallback");
    }

    @Test
    void authorize_uri_appends_query_when_base_already_has_one() {
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("https://provider.example/authorize?audience=api",
                "http://localhost/token", List.of("read"));
        OAuthInitContext ctx = ctx("S", "https://vance/callback");

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString())
                .startsWith("https://provider.example/authorize?audience=api&")
                .contains("&client_id=my-app");
    }

    @Test
    void authorize_uri_omits_scope_when_empty() {
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("https://provider.example/authorize",
                "http://localhost/token", List.of());
        OAuthInitContext ctx = ctx("S", "https://vance/callback");

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString()).doesNotContain("scope=");
    }

    @Test
    void authorize_uri_fails_when_endpoint_missing() {
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg(/*authorizeUrl*/ null,
                "http://localhost/token", List.of());
        OAuthInitContext ctx = ctx("S", "https://vance/callback");

        assertThatThrownBy(() -> provider.buildAuthorizeUri(cfg, ctx))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("authorize URL");
    }

    // ─────── exchangeCode ───────

    @Test
    void exchange_code_posts_form_body_and_returns_tokens() {
        nextResponse.set(TokenResponse.ok("""
                {"access_token":"AT","refresh_token":"RT","expires_in":3600,"scope":"openid email","token_type":"Bearer"}
                """));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://localhost:" + port + "/authorize",
                "http://localhost:" + port + "/token", List.of("openid", "email"));
        OAuthInitContext ctx = ctx("STATE", "https://vance/callback");

        Instant before = Instant.now();
        OAuthTokenSet tokens = provider.exchangeCode(cfg, "AUTH-CODE", ctx);
        Instant after = Instant.now();

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.refreshToken()).isEqualTo("RT");
        assertThat(tokens.expiresAt()).isBetween(
                before.plusSeconds(3599), after.plusSeconds(3601));
        assertThat(tokens.extraClaims())
                .containsEntry("scope", "openid email")
                .containsEntry("token_type", "Bearer");

        RecordedRequest req = lastRequest.get();
        assertThat(req.method).isEqualTo("POST");
        assertThat(req.contentType).isEqualTo("application/x-www-form-urlencoded");
        assertThat(req.form)
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "AUTH-CODE")
                .containsEntry("redirect_uri", "https://vance/callback")
                .containsEntry("client_id", "my-app")
                .containsEntry("client_secret", "shh-secret");
    }

    @Test
    void exchange_code_carries_no_expires_at_when_absent() {
        nextResponse.set(TokenResponse.ok("""
                {"access_token":"AT"}
                """));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://localhost:" + port + "/authorize",
                "http://localhost:" + port + "/token", List.of());

        OAuthTokenSet tokens = provider.exchangeCode(cfg, "C", ctx("S", "https://v/cb"));

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.refreshToken()).isNull();
        assertThat(tokens.expiresAt()).isNull();
    }

    @Test
    void exchange_code_throws_when_response_missing_access_token() {
        nextResponse.set(TokenResponse.ok("""
                {"refresh_token":"RT"}
                """));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://x/authorize",
                "http://localhost:" + port + "/token", List.of());

        assertThatThrownBy(() ->
                provider.exchangeCode(cfg, "C", ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("missing 'access_token'");
    }

    @Test
    void exchange_code_throws_on_http_error() {
        nextResponse.set(new TokenResponse(400, "{\"error\":\"invalid_grant\"}"));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://x/authorize",
                "http://localhost:" + port + "/token", List.of());

        assertThatThrownBy(() ->
                provider.exchangeCode(cfg, "bad", ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid_grant");
    }

    @Test
    void exchange_code_throws_on_oauth_error_in_200_body() {
        // RFC 6749 §5.2 — some providers tunnel errors as 200-OK with
        // an "error" field in the JSON body.
        nextResponse.set(TokenResponse.ok("""
                {"error":"invalid_client","error_description":"bad creds"}
                """));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://x/authorize",
                "http://localhost:" + port + "/token", List.of());

        assertThatThrownBy(() ->
                provider.exchangeCode(cfg, "c", ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("invalid_client")
                .hasMessageContaining("bad creds");
    }

    @Test
    void exchange_code_throws_on_unparseable_body() {
        nextResponse.set(TokenResponse.ok("<html>not json</html>"));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://x/authorize",
                "http://localhost:" + port + "/token", List.of());

        assertThatThrownBy(() ->
                provider.exchangeCode(cfg, "c", ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("not valid JSON");
    }

    // ─────── refresh ───────

    @Test
    void refresh_posts_correct_form_body() {
        nextResponse.set(TokenResponse.ok("""
                {"access_token":"NEW-AT","refresh_token":"NEW-RT","expires_in":7200}
                """));
        GenericOAuth2Provider provider = newProvider();
        OAuthProviderConfig cfg = cfg("http://x/authorize",
                "http://localhost:" + port + "/token", List.of("openid"));

        OAuthTokenSet tokens = provider.refresh(cfg, "OLD-RT");

        assertThat(tokens.accessToken()).isEqualTo("NEW-AT");
        assertThat(tokens.refreshToken()).isEqualTo("NEW-RT");
        assertThat(tokens.expiresAt()).isAfter(Instant.now().plusSeconds(7100));

        RecordedRequest req = lastRequest.get();
        assertThat(req.form)
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "OLD-RT")
                .containsEntry("client_id", "my-app")
                .containsEntry("client_secret", "shh-secret");
    }

    // ─────── Helpers ───────

    private GenericOAuth2Provider newProvider() {
        return new GenericOAuth2Provider(new PackHttpClient());
    }

    private OAuthProviderConfig cfg(String authorizeUrl, String tokenUrl, List<String> scopes) {
        return new OAuthProviderConfig(
                "test-provider", "generic-oauth2",
                /*discoveryUrl*/ null,
                authorizeUrl, tokenUrl,
                "my-app", "shh-secret",
                new ArrayList<>(scopes), new LinkedHashMap<>());
    }

    private OAuthInitContext ctx(String state, String redirectUri) {
        return new OAuthInitContext("acme", "alice", state, redirectUri, null);
    }

    // ──────────────────── HTTP mock plumbing ────────────────────

    private void handleToken(HttpExchange exchange) throws IOException {
        callCount.incrementAndGet();
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest req = new RecordedRequest();
        req.method = exchange.getRequestMethod();
        req.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        req.form = parseForm(body);
        lastRequest.set(req);

        TokenResponse next = nextResponse.get();
        if (next == null) next = TokenResponse.ok("{}");

        byte[] bytes = next.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(next.status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static class RecordedRequest {
        String method;
        String contentType;
        Map<String, String> form = Map.of();
    }

    private record TokenResponse(int status, String body) {
        static TokenResponse ok(String body) {
            return new TokenResponse(200, body);
        }
    }
}
