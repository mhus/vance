package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link OAuthProviderLoader#validateYaml}.
 * Bypasses {@code DocumentService} and {@code SettingService} via the
 * public validate entry point — no Spring context needed.
 */
class OAuthProviderLoaderTest {

    private final OAuthProviderLoader loader = new OAuthProviderLoader(
            /*documentService*/ null, /*settingService*/ null);

    @Test
    void parses_minimal_oidc_config() {
        OAuthProviderConfig cfg = loader.validateYaml("keycloak", """
                type: oidc
                clientId: vance
                discoveryUrl: "https://sso.acme.com/realms/acme/.well-known/openid-configuration"
                scopes: [openid, profile, email]
                """);

        assertThat(cfg.providerId()).isEqualTo("keycloak");
        assertThat(cfg.typeId()).isEqualTo("oidc");
        assertThat(cfg.clientId()).isEqualTo("vance");
        assertThat(cfg.discoveryUrl())
                .isEqualTo("https://sso.acme.com/realms/acme/.well-known/openid-configuration");
        assertThat(cfg.scopes()).containsExactly("openid", "profile", "email");
        // Loader does not resolve the secret — that happens at load(...) time.
        assertThat(cfg.clientSecret()).isEmpty();
    }

    @Test
    void parses_generic_oauth2_config() {
        OAuthProviderConfig cfg = loader.validateYaml("github", """
                type: generic-oauth2
                clientId: "Iv1.abcd1234"
                authorizeUrl: "https://github.com/login/oauth/authorize"
                tokenUrl: "https://github.com/login/oauth/access_token"
                scopes: [repo, user]
                """);

        assertThat(cfg.typeId()).isEqualTo("generic-oauth2");
        assertThat(cfg.authorizeUrl()).isEqualTo("https://github.com/login/oauth/authorize");
        assertThat(cfg.tokenUrl()).isEqualTo("https://github.com/login/oauth/access_token");
        assertThat(cfg.scopes()).containsExactly("repo", "user");
    }

    @Test
    void parses_slack_config_with_extra() {
        OAuthProviderConfig cfg = loader.validateYaml("slack", """
                type: slack
                clientId: "1234.5678"
                authorizeUrl: "https://slack.com/oauth/v2/authorize"
                tokenUrl: "https://slack.com/api/oauth.v2.access"
                scopes: ["channels:read", "chat:write"]
                extra:
                  workspace: "acme-team"
                """);

        assertThat(cfg.typeId()).isEqualTo("slack");
        assertThat(cfg.scopes()).containsExactly("channels:read", "chat:write");
        assertThat(cfg.extra()).containsEntry("workspace", "acme-team");
    }

    @Test
    void google_accepts_discovery_only() {
        OAuthProviderConfig cfg = loader.validateYaml("google", """
                type: google
                clientId: "1234.apps.googleusercontent.com"
                discoveryUrl: "https://accounts.google.com/.well-known/openid-configuration"
                scopes: [openid, email]
                """);

        assertThat(cfg.typeId()).isEqualTo("google");
        assertThat(cfg.discoveryUrl()).isNotNull();
    }

    @Test
    void google_accepts_explicit_endpoints() {
        OAuthProviderConfig cfg = loader.validateYaml("google-manual", """
                type: google
                clientId: "x.apps.googleusercontent.com"
                authorizeUrl: "https://accounts.google.com/o/oauth2/v2/auth"
                tokenUrl: "https://oauth2.googleapis.com/token"
                scopes: [openid]
                """);

        assertThat(cfg.typeId()).isEqualTo("google");
        assertThat(cfg.authorizeUrl()).isNotNull();
    }

    @Test
    void normalises_provider_id_to_lowercase() {
        OAuthProviderConfig cfg = loader.validateYaml("Slack", """
                type: slack
                clientId: x
                authorizeUrl: https://slack.com/x
                tokenUrl: https://slack.com/y
                """);

        assertThat(cfg.providerId()).isEqualTo("slack");
    }

    @Test
    void path_for_uses_prefix_and_suffix() {
        assertThat(OAuthProviderLoader.pathFor("Slack-Workspace"))
                .isEqualTo("oauth/slack-workspace.yaml");
    }

    @Test
    void client_secret_key_follows_convention() {
        assertThat(OAuthProviderLoader.clientSecretKey("slack"))
                .isEqualTo("oauth.slack.client_secret");
    }

    @Test
    void rejects_missing_type() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                clientId: x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejects_missing_client_id() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: oidc
                discoveryUrl: https://x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void rejects_oidc_without_discovery_url() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: oidc
                clientId: x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("discoveryUrl");
    }

    @Test
    void rejects_generic_oauth2_without_endpoints() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: generic-oauth2
                clientId: x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("authorizeUrl");
    }

    @Test
    void rejects_inline_client_secret() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: oidc
                clientId: x
                clientSecret: leaked-into-yaml
                discoveryUrl: https://x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("clientSecret");
    }

    @Test
    void rejects_inline_client_secret_snake_case() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: oidc
                clientId: x
                client_secret: also-leaked
                discoveryUrl: https://x
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("clientSecret");
    }

    @Test
    void rejects_empty_yaml() {
        assertThatThrownBy(() -> loader.validateYaml("x", "  \n"))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_non_map_root() {
        assertThatThrownBy(() -> loader.validateYaml("x", "- a\n- b\n"))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("top-level map");
    }

    @Test
    void rejects_blank_scope() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: oidc
                clientId: x
                discoveryUrl: https://x
                scopes: [openid, ""]
                """))
                .isInstanceOf(OAuthProviderLoader.OAuthProviderParseException.class)
                .hasMessageContaining("scopes");
    }
}
