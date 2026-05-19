package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.toolpack.core.PackHttpClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * Tests for {@link AtlassianOAuthProvider} — verifies that after the
 * token exchange the provider fetches {@code /oauth/token/accessible-resources}
 * and stashes {@code cloud_id} (+ name/url) in {@code extraClaims}.
 * Failures of that secondary call must NOT fail the whole flow — the
 * access token is still useful, just without a cloud_id.
 */
class AtlassianOAuthProviderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger resourcesCalls = new AtomicInteger();
    private final AtomicReference<String> resourcesBody = new AtomicReference<>();
    private final AtomicReference<Integer> resourcesStatus = new AtomicReference<>(200);
    private final AtomicReference<String> tokenBody = new AtomicReference<>("""
            {"access_token":"AT","refresh_token":"RT","expires_in":3600,"scope":"read:jira-work"}
            """);

    @BeforeEach
    void startServer() throws IOException {
        tokenCalls.set(0);
        resourcesCalls.set(0);
        resourcesStatus.set(200);
        resourcesBody.set("""
                [
                  {
                    "id": "cloud-uuid-1",
                    "name": "Acme Cloud",
                    "url": "https://acme.atlassian.net",
                    "scopes": ["read:jira-work"]
                  }
                ]
                """);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/token", this::handleToken);
        server.createContext("/accessible-resources", this::handleResources);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void type_id_is_atlassian() {
        assertThat(new AtlassianOAuthProvider(new PackHttpClient()).typeId()).isEqualTo("atlassian");
    }

    @Test
    void authorize_uri_appends_audience_and_prompt_consent() {
        // Atlassian's /authorize endpoint requires 'audience' on every
        // request and we always force prompt=consent so scope changes
        // propagate on reconnect.
        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());

        java.net.URI uri = provider.buildAuthorizeUri(cfg(), ctx());

        assertThat(uri.toString())
                .contains("audience=api.atlassian.com")
                .contains("prompt=consent");
    }

    @Test
    void authorize_uri_honours_audience_override_from_extra() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("audience", "api-staging.atlassian.com");
        OAuthProviderConfig stagingCfg = new OAuthProviderConfig(
                "atlassian", "atlassian", null,
                "https://auth.atlassian.com/authorize",
                "https://auth.atlassian.com/oauth/token",
                "client-id", "shh",
                new ArrayList<>(List.of("read:jira-work")),
                extra);

        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());
        java.net.URI uri = provider.buildAuthorizeUri(stagingCfg, ctx());

        assertThat(uri.toString()).contains("audience=api-staging.atlassian.com");
    }

    @Test
    void exchange_code_attaches_cloud_id_from_accessible_resources() {
        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());

        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "AUTH-CODE", ctx());

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.refreshToken()).isEqualTo("RT");
        assertThat(tokens.extraClaims())
                .containsEntry("cloud_id", "cloud-uuid-1")
                .containsEntry("site_name", "Acme Cloud")
                .containsEntry("site_url", "https://acme.atlassian.net")
                .containsKey("accessible_resources");
        assertThat(tokens.extraClaims().get("accessible_resources"))
                .contains("cloud-uuid-1");
        assertThat(resourcesCalls.get()).isEqualTo(1);
    }

    @Test
    void multi_site_grant_picks_first_and_keeps_full_list() {
        resourcesBody.set("""
                [
                  {"id":"site-a","name":"A","url":"https://a","scopes":["read"]},
                  {"id":"site-b","name":"B","url":"https://b","scopes":["read"]}
                ]
                """);

        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "C", ctx());

        assertThat(tokens.extraClaims().get("cloud_id")).isEqualTo("site-a");
        assertThat(tokens.extraClaims().get("accessible_resources"))
                .contains("site-a")
                .contains("site-b");
    }

    @Test
    void accessible_resources_http_error_does_not_fail_the_flow() {
        // The access token is still usable for endpoints that don't
        // need a cloud_id (less common, but possible). Surface the
        // tokens without cloud_id and log a warning — never block the
        // user's connect attempt for a secondary metadata fetch.
        resourcesStatus.set(500);

        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "C", ctx());

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.extraClaims()).doesNotContainKey("cloud_id");
    }

    @Test
    void empty_resources_array_yields_no_cloud_id() {
        resourcesBody.set("[]");

        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "C", ctx());

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.extraClaims()).doesNotContainKey("cloud_id");
    }

    @Test
    void refresh_does_not_re_fetch_accessible_resources() {
        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());

        provider.refresh(cfg(), "OLD-RT");

        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(resourcesCalls.get())
                .as("the grant's site set is stable across refresh")
                .isZero();
    }

    @Test
    void uses_extra_accessible_resources_url_override() {
        // For staging environments the tenant can override the default
        // production URL via YAML extra.
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("accessibleResourcesUrl", "http://localhost:" + port + "/accessible-resources");
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "atlassian", "atlassian",
                null,
                "https://auth.atlassian.com/authorize",
                "http://localhost:" + port + "/token",
                "client-id", "shh",
                new ArrayList<>(),
                extra);

        AtlassianOAuthProvider provider = new AtlassianOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg, "C", ctx());

        assertThat(tokens.extraClaims()).containsEntry("cloud_id", "cloud-uuid-1");
    }

    // ─── helpers ───

    private OAuthProviderConfig cfg() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("accessibleResourcesUrl",
                "http://localhost:" + port + "/accessible-resources");
        return new OAuthProviderConfig(
                "atlassian", "atlassian",
                null,
                "https://auth.atlassian.com/authorize",
                "http://localhost:" + port + "/token",
                "client-id", "shh",
                new ArrayList<>(List.of("read:jira-work")),
                extra);
    }

    private static OAuthInitContext ctx() {
        return new OAuthInitContext("acme", "alice", "S", "https://v/cb", null);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenCalls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = tokenBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleResources(HttpExchange exchange) throws IOException {
        resourcesCalls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        int status = resourcesStatus.get();
        byte[] bytes = resourcesBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
