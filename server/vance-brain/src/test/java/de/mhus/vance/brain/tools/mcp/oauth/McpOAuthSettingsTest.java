package de.mhus.vance.brain.tools.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.rest.AuthSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpOAuthSettingsTest {

    private static Map<String, Object> params(Map<String, Object> auth) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("transport", "http");
        p.put("url", "https://mcp.example.com/mcp");
        p.put("auth", auth);
        return p;
    }

    @Test
    void of_returnsEmpty_forOtherAuthTypes() {
        assertThat(McpOAuthSettings.of(params(Map.of("type", "bearer", "token", "x"))))
                .isEmpty();
        assertThat(McpOAuthSettings.of(Map.of("transport", "http", "url", "https://x/mcp")))
                .isEmpty();
    }

    @Test
    void of_parsesOAuthBlock_withDefaults() {
        McpOAuthSettings s =
                McpOAuthSettings.of(params(Map.of("type", "oauth"))).orElseThrow();

        assertThat(s.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(s.loginScope()).isEqualTo(McpOAuthSettings.LoginScope.USER);
        assertThat(s.scopes()).isEmpty();
        assertThat(s.clientId()).isNull();
    }

    @Test
    void of_parsesLoginScopeScopesAndClient() {
        McpOAuthSettings s = McpOAuthSettings.of(params(Map.of(
                        "type", "OAuth",
                        "loginScope", "Project",
                        "scopes", "read write",
                        "clientId", "abc",
                        "clientSecret", "{{secret:tenant:x}}")))
                .orElseThrow();

        assertThat(s.loginScope()).isEqualTo(McpOAuthSettings.LoginScope.PROJECT);
        assertThat(s.scopes()).containsExactly("read", "write");
        assertThat(s.clientId()).isEqualTo("abc");
        assertThat(s.clientSecret()).isEqualTo("{{secret:tenant:x}}");
    }

    @Test
    void of_rejectsUnknownLoginScope() {
        assertThatThrownBy(() -> McpOAuthSettings.of(params(Map.of("type", "oauth", "loginScope", "team"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loginScope");
    }

    @Test
    void of_rejectsSecretWithoutClientId() {
        assertThatThrownBy(() -> McpOAuthSettings.of(params(Map.of("type", "oauth", "clientSecret", "s"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsOAuthWithoutUrl() {
        assertThatThrownBy(() -> McpOAuthSettings.of(Map.of("transport", "stdio", "auth", Map.of("type", "oauth"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void providerId_isStablePerUrl_andDistinctAcrossServers() {
        String a = McpOAuthSettings.providerIdFor("https://mcp.example.com/mcp");
        String b = McpOAuthSettings.providerIdFor("https://mcp.example.com/mcp");
        String c = McpOAuthSettings.providerIdFor("https://mcp.example.com/other");

        assertThat(a).isEqualTo(b).startsWith("mcp-mcp-example-com-").matches("[a-z0-9-]+");
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    void owner_followsLoginScope() {
        McpOAuthSettings user =
                McpOAuthSettings.of(params(Map.of("type", "oauth"))).orElseThrow();
        McpOAuthSettings project = McpOAuthSettings.of(params(Map.of("type", "oauth", "loginScope", "project")))
                .orElseThrow();
        McpOAuthSettings tenant = McpOAuthSettings.of(params(Map.of("type", "oauth", "loginScope", "tenant")))
                .orElseThrow();

        assertThat(user.owner("p1", "alice")).isEqualTo(OAuthTokenOwner.user("alice"));
        assertThat(project.owner("p1", "alice")).isEqualTo(OAuthTokenOwner.project("p1"));
        assertThat(tenant.owner("p1", "alice")).isEqualTo(OAuthTokenOwner.tenant());
    }

    @Test
    void effectiveConfig_translatesOAuthIntoScopedBearerTemplate() {
        Map<String, Object> p = params(Map.of("type", "oauth", "loginScope", "tenant"));

        McpConfig cfg = McpOAuthSettings.effectiveConfig(p);

        assertThat(cfg.auth().type()).isEqualTo(AuthSpec.Type.BEARER);
        assertThat(cfg.auth().token())
                .isEqualTo("{{secret:tenant:oauth." + McpOAuthSettings.providerIdFor("https://mcp.example.com/mcp")
                        + ".access_token}}");
    }

    @Test
    void effectiveConfig_keepsOtherAuthUntouched() {
        McpConfig cfg = McpOAuthSettings.effectiveConfig(params(Map.of("type", "bearer", "token", "T")));

        assertThat(cfg.auth().type()).isEqualTo(AuthSpec.Type.BEARER);
        assertThat(cfg.auth().token()).isEqualTo("T");
        assertThat(List.of(cfg.url())).containsExactly("https://mcp.example.com/mcp");
    }
}
