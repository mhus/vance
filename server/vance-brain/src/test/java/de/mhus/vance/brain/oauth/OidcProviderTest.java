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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests for {@link OidcProvider} — discovery fetch
 * + caching, fallback when YAML pins endpoints, error mapping.
 */
class OidcProviderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger discoveryCalls = new AtomicInteger();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicReference<String> discoveryBody = new AtomicReference<>();
    private final AtomicReference<Integer> discoveryStatus = new AtomicReference<>(200);

    @BeforeEach
    void startServer() throws IOException {
        discoveryCalls.set(0);
        tokenCalls.set(0);
        discoveryStatus.set(200);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/realms/acme/.well-known/openid-configuration",
                this::handleDiscovery);
        server.createContext("/realms/acme/protocol/openid-connect/token",
                this::handleToken);
        server.setExecutor(null);
        server.start();
        // Set the default body AFTER the server starts — defaultDiscoveryBody()
        // bakes the port number in, which is only known post-bind.
        discoveryBody.set(defaultDiscoveryBody());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void type_id_is_oidc() {
        assertThat(new OidcProvider().typeId()).isEqualTo("oidc");
    }

    @Test
    void authorize_uri_uses_discovery_endpoint() {
        OidcProvider provider = newProvider();
        URI uri = provider.buildAuthorizeUri(cfg(), ctx("S", "https://v/cb"));

        assertThat(uri.toString())
                .startsWith("http://localhost:" + port + "/realms/acme/protocol/openid-connect/auth?")
                .contains("client_id=vance")
                .contains("state=S");
        assertThat(discoveryCalls.get()).isEqualTo(1);
    }

    @Test
    void discovery_is_cached_per_url() {
        OidcProvider provider = newProvider();
        provider.buildAuthorizeUri(cfg(), ctx("S1", "https://v/cb"));
        provider.buildAuthorizeUri(cfg(), ctx("S2", "https://v/cb"));
        provider.buildAuthorizeUri(cfg(), ctx("S3", "https://v/cb"));

        assertThat(discoveryCalls.get())
                .as("discovery URL is fetched once and cached")
                .isEqualTo(1);
    }

    @Test
    void invalidate_discovery_drops_cache_for_url() {
        OidcProvider provider = newProvider();
        OAuthProviderConfig cfg = cfg();

        provider.buildAuthorizeUri(cfg, ctx("S1", "https://v/cb"));
        provider.invalidateDiscovery(cfg.discoveryUrl());
        provider.buildAuthorizeUri(cfg, ctx("S2", "https://v/cb"));

        assertThat(discoveryCalls.get()).isEqualTo(2);
    }

    @Test
    void explicit_yaml_endpoints_override_discovery() {
        // When the YAML pins authorizeUrl/tokenUrl, the provider must
        // honour them even if discoveryUrl is also set. Useful when the
        // discovery doc carries an internal-cluster URL that the browser
        // can't reach.
        OidcProvider provider = newProvider();
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "keycloak", "oidc",
                discoveryUrl(),
                "https://external.example/authorize",
                "https://external.example/token",
                "vance", "shh",
                new ArrayList<>(List.of("openid")), new LinkedHashMap<>());

        URI uri = provider.buildAuthorizeUri(cfg, ctx("S", "https://v/cb"));

        assertThat(uri.toString()).startsWith("https://external.example/authorize?");
        assertThat(discoveryCalls.get())
                .as("explicit authorize URL skips discovery entirely")
                .isZero();
    }

    @Test
    void token_exchange_uses_discovered_endpoint() {
        OidcProvider provider = newProvider();

        OAuthTokenSet tokens = provider.exchangeCode(
                cfg(), "AUTH-CODE", ctx("S", "https://v/cb"));

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void throws_when_discovery_returns_non_2xx() {
        discoveryStatus.set(503);
        OidcProvider provider = newProvider();

        assertThatThrownBy(() ->
                provider.buildAuthorizeUri(cfg(), ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void throws_when_discovery_misses_endpoints() {
        discoveryBody.set("""
                {"issuer":"http://x"}
                """);
        OidcProvider provider = newProvider();

        assertThatThrownBy(() ->
                provider.buildAuthorizeUri(cfg(), ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("missing 'authorization_endpoint'");
    }

    @Test
    void throws_when_discovery_url_missing() {
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "keycloak", "oidc",
                /*discoveryUrl*/ null,
                /*authorizeUrl*/ null,
                /*tokenUrl*/ null,
                "vance", "shh",
                new ArrayList<>(), new LinkedHashMap<>());

        OidcProvider provider = newProvider();

        assertThatThrownBy(() ->
                provider.buildAuthorizeUri(cfg, ctx("S", "https://v/cb")))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("no authorize URL");
    }

    // ──────────────────── Helpers ────────────────────

    private OidcProvider newProvider() {
        return new OidcProvider(new PackHttpClient());
    }

    private OAuthProviderConfig cfg() {
        return new OAuthProviderConfig(
                "keycloak", "oidc",
                discoveryUrl(),
                /*authorizeUrl*/ null,
                /*tokenUrl*/ null,
                "vance", "shh",
                new ArrayList<>(List.of("openid", "email")), new LinkedHashMap<>());
    }

    private String discoveryUrl() {
        return "http://localhost:" + port + "/realms/acme/.well-known/openid-configuration";
    }

    private OAuthInitContext ctx(String state, String redirectUri) {
        return new OAuthInitContext("acme", "alice", state, redirectUri, null);
    }

    private String defaultDiscoveryBody() {
        return ("""
                {
                  "issuer": "http://localhost:%d/realms/acme",
                  "authorization_endpoint": "http://localhost:%d/realms/acme/protocol/openid-connect/auth",
                  "token_endpoint": "http://localhost:%d/realms/acme/protocol/openid-connect/token"
                }
                """).formatted(port, port, port);
    }

    // ─── HTTP mock plumbing ───

    private void handleDiscovery(HttpExchange exchange) throws IOException {
        discoveryCalls.incrementAndGet();
        // Capture the port-bound body at the moment the request lands;
        // the field starts blank and is filled by @BeforeEach after the
        // server starts.
        String body = discoveryBody.get();
        if (body == null || body.isBlank()) body = defaultDiscoveryBody();
        int status = discoveryStatus.get();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenCalls.incrementAndGet();
        // We don't introspect the request body in OIDC tests — the
        // GenericOAuth2ProviderTest covers form-encoding details.
        exchange.getRequestBody().readAllBytes();
        String body = """
                {"access_token":"AT","refresh_token":"RT","expires_in":3600,"scope":"openid email","token_type":"Bearer"}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
