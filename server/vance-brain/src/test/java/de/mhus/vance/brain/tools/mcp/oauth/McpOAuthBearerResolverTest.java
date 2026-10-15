package de.mhus.vance.brain.tools.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The transport resolver of an OAuth MCP server: the bearer from the refresher, nothing else changed. */
class McpOAuthBearerResolverTest {

    private static final String URL = "https://mcp.example.com/mcp";
    private static final String PID = McpOAuthSettings.providerIdFor(URL);

    private final OAuthTokenRefresher refresher = mock(OAuthTokenRefresher.class);
    private final SecretResolver delegate = mock(SecretResolver.class);

    private static Map<String, Object> params(String loginScope) {
        return Map.of("transport", "http", "url", URL, "auth", Map.of("type", "oauth", "loginScope", loginScope));
    }

    private static ToolInvocationContext ctx(String project, String user) {
        return new ToolInvocationContext("acme", project, null, null, user);
    }

    @Test
    void nonOAuthTool_keepsTheDelegate() {
        Map<String, Object> bearer =
                Map.of("transport", "http", "url", URL, "auth", Map.of("type", "bearer", "token", "T"));

        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, bearer)).isSameAs(delegate);
    }

    @Test
    void bearer_comesFromTheRefresherForTheLoginScopesAccount() {
        when(refresher.resolveAccessTokenFor("acme", OAuthTokenOwner.tenant(), PID))
                .thenReturn("tenant-at");
        when(refresher.resolveAccessTokenFor("acme", OAuthTokenOwner.project("team"), PID))
                .thenReturn("project-at");
        when(refresher.resolveAccessTokenFor("acme", OAuthTokenOwner.user("alice"), PID))
                .thenReturn("user-at");

        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, params("tenant"))
                        .resolveForConnector(McpOAuthSettings.BEARER_REFERENCE, ctx("team", "alice")))
                .isEqualTo("tenant-at");
        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, params("project"))
                        .resolveForConnector(McpOAuthSettings.BEARER_REFERENCE, ctx("team", "alice")))
                .isEqualTo("project-at");
        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, params("user"))
                        .resolveForConnector(McpOAuthSettings.BEARER_REFERENCE, ctx("team", "alice")))
                .isEqualTo("user-at");
    }

    @Test
    void restrictedPath_neverYieldsTheBearer() {
        SecretResolver r = McpOAuthBearerResolver.forTool(delegate, refresher, params("tenant"));

        r.resolve(McpOAuthSettings.BEARER_REFERENCE, ctx("team", "alice"));

        verify(refresher, never()).resolveAccessTokenFor(any(), any(), any());
        verify(delegate).substitute(McpOAuthSettings.BEARER_REFERENCE, ctx("team", "alice"));
    }

    @Test
    void otherValues_goToTheDelegate() {
        when(delegate.substituteForConnector("{{secret:x}}", ctx("team", "alice")))
                .thenReturn("v");

        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, params("user"))
                        .resolveForConnector("{{secret:x}}", ctx("team", "alice")))
                .isEqualTo("v");
    }

    @Test
    void projectLoginInTenantProject_sendsNoToken() {
        assertThat(McpOAuthBearerResolver.forTool(delegate, refresher, params("project"))
                        .resolveForConnector(McpOAuthSettings.BEARER_REFERENCE, ctx("_tenant", "alice")))
                .isNull();
        verify(refresher, never()).resolveAccessTokenFor(any(), any(), any());
    }

    @Test
    void oauthTool_withoutRefresher_failsLoudly() {
        assertThatThrownBy(() -> McpOAuthBearerResolver.forTool(delegate, null, params("user")))
                .isInstanceOf(IllegalStateException.class);
    }
}
