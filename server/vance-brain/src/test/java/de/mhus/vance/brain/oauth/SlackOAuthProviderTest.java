package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SlackOAuthProvider} — verifies that the Slack v2
 * nested-token-response shape (authed_user) is parsed correctly and
 * that the {@code ok=false} error path surfaces as
 * {@link OAuthFlowException}.
 */
class SlackOAuthProviderTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> responseBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/token", this::handle);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void type_id_is_slack() {
        assertThat(new SlackOAuthProvider(new PackHttpClient()).typeId()).isEqualTo("slack");
    }

    @Test
    void parses_nested_authed_user_token() {
        responseBody.set("""
                {
                  "ok": true,
                  "access_token": "xoxb-bot-token",
                  "token_type": "bot",
                  "scope": "channels:read,chat:write",
                  "bot_user_id": "B123",
                  "app_id": "A1",
                  "team": {"id": "T123", "name": "Acme"},
                  "authed_user": {
                    "id": "U456",
                    "access_token": "xoxp-user-token",
                    "refresh_token": "xoxe.user-refresh",
                    "expires_in": 43200,
                    "scope": "im:read,im:write"
                  }
                }
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "AUTH-CODE", ctx());

        assertThat(tokens.accessToken())
                .as("we want the USER token, not the bot token")
                .isEqualTo("xoxp-user-token");
        assertThat(tokens.refreshToken()).isEqualTo("xoxe.user-refresh");
        assertThat(tokens.expiresAt()).isNotNull();

        assertThat(tokens.extraClaims())
                .containsEntry("scope", "im:read,im:write")
                .containsEntry("authed_user_id", "U456")
                .containsEntry("team_id", "T123")
                .containsEntry("team_name", "Acme")
                .containsEntry("bot_access_token", "xoxb-bot-token")
                .containsEntry("bot_scope", "channels:read,chat:write")
                .containsEntry("bot_user_id", "B123")
                .containsEntry("app_id", "A1");
    }

    @Test
    void non_rotating_tokens_yield_null_expires_at() {
        // Without Token Rotation enabled, Slack v2 omits expires_in /
        // refresh_token entirely — tokens never expire.
        responseBody.set("""
                {
                  "ok": true,
                  "access_token": "xoxb-bot",
                  "team": {"id": "T"},
                  "authed_user": {"id": "U", "access_token": "xoxp-user", "scope": "im:read"}
                }
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());
        OAuthTokenSet tokens = provider.exchangeCode(cfg(), "C", ctx());

        assertThat(tokens.accessToken()).isEqualTo("xoxp-user");
        assertThat(tokens.refreshToken()).isNull();
        assertThat(tokens.expiresAt()).isNull();
    }

    @Test
    void ok_false_with_error_field_surfaces_error_code() {
        // Slack returns {ok:false, error:"…"} on auth failures. The
        // generic RFC 6749 §5.2 detector in GenericOAuth2Provider
        // catches the {@code error} field first — that's the right
        // path, with Slack's error code surfaced verbatim.
        responseBody.set("""
                {"ok":false,"error":"invalid_code"}
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());

        assertThatThrownBy(() -> provider.exchangeCode(cfg(), "bad", ctx()))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("invalid_code");
    }

    @Test
    void ok_false_without_error_field_yields_slack_specific_message() {
        // Defensive — when Slack returns ok=false without a top-level
        // error code, the Slack-specific parser still trips.
        responseBody.set("""
                {"ok":false}
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());

        assertThatThrownBy(() -> provider.exchangeCode(cfg(), "bad", ctx()))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("ok=false");
    }

    @Test
    void missing_authed_user_throws_clear_message() {
        // A bot-only OAuth (no user grant) would land here — Vance
        // acts on behalf of the user, so we reject the install.
        responseBody.set("""
                {"ok": true, "access_token": "xoxb-bot-only", "team": {"id": "T"}}
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());

        assertThatThrownBy(() -> provider.exchangeCode(cfg(), "C", ctx()))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("authed_user");
    }

    @Test
    void missing_authed_user_access_token_throws() {
        responseBody.set("""
                {"ok": true, "team": {"id": "T"},
                 "authed_user": {"id": "U", "scope": "im:read"}}
                """);

        SlackOAuthProvider provider = new SlackOAuthProvider(new PackHttpClient());

        assertThatThrownBy(() -> provider.exchangeCode(cfg(), "C", ctx()))
                .isInstanceOf(OAuthFlowException.class)
                .hasMessageContaining("authed_user.access_token");
    }

    // ─── helpers ───

    private OAuthProviderConfig cfg() {
        return new OAuthProviderConfig(
                "slack", "slack",
                /*discoveryUrl*/ null,
                "https://slack.com/oauth/v2/authorize",
                "http://localhost:" + port + "/token",
                "client-id", "shh",
                new ArrayList<>(List.of("channels:read")),
                new LinkedHashMap<>());
    }

    private static OAuthInitContext ctx() {
        return new OAuthInitContext("acme", "alice", "S", "https://v/cb", null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Drain the request body so the client gets a clean close.
        exchange.getRequestBody().readAllBytes();
        String body = responseBody.get();
        if (body == null) body = "{}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
