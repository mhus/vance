package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GoogleOAuthProvider} — verifies that the
 * authorize-URL carries {@code access_type=offline} and
 * {@code prompt=consent} (without these, Google omits the refresh
 * token and our auto-refresh path dies an hour later).
 */
class GoogleOAuthProviderTest {

    @Test
    void type_id_is_google() {
        assertThat(new GoogleOAuthProvider(new PackHttpClient()).typeId()).isEqualTo("google");
    }

    @Test
    void authorize_uri_adds_offline_and_consent_params() {
        GoogleOAuthProvider provider = new GoogleOAuthProvider(new PackHttpClient());
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "google", "google",
                /*discoveryUrl*/ null,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "client-id", "shh",
                new ArrayList<>(List.of("openid", "email")),
                new LinkedHashMap<>());
        OAuthInitContext ctx = new OAuthInitContext(
                "acme", "alice", "STATE", "https://vance/callback", null);

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString())
                .contains("access_type=offline")
                .contains("prompt=consent");
    }

    @Test
    void authorize_uri_passes_extra_params_through() {
        GoogleOAuthProvider provider = new GoogleOAuthProvider(new PackHttpClient());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("include_granted_scopes", "true");
        extra.put("login_hint", "user@example.com");
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "google", "google",
                null,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "client-id", "shh",
                new ArrayList<>(),
                extra);
        OAuthInitContext ctx = new OAuthInitContext(
                "acme", "alice", "STATE", "https://vance/callback", null);

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString())
                .contains("include_granted_scopes=true")
                .contains("login_hint=user%40example.com");
    }

    @Test
    void extra_overrides_default_offline_param() {
        // A tenant who explicitly sets access_type via YAML extra
        // should win over the default — useful for tools that don't
        // need offline access and want to skip the consent screen.
        GoogleOAuthProvider provider = new GoogleOAuthProvider(new PackHttpClient());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("access_type", "online");
        OAuthProviderConfig cfg = new OAuthProviderConfig(
                "google", "google",
                null,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "client-id", "shh",
                new ArrayList<>(),
                extra);
        OAuthInitContext ctx = new OAuthInitContext(
                "acme", "alice", "STATE", "https://vance/callback", null);

        URI uri = provider.buildAuthorizeUri(cfg, ctx);

        assertThat(uri.toString())
                .contains("access_type=online")
                .doesNotContain("access_type=offline");
    }
}
