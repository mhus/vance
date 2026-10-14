package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.access.IntegrationScopeRegistry;
import de.mhus.vance.brain.access.NarrowedCredentialCheck;
import de.mhus.vance.brain.mcpserver.McpGlobalScopeProfile;
import de.mhus.vance.brain.mcpserver.McpProjectScopeProfile;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthAccessTokenAuthServiceTest {

    private static final String BASE = "https://vance.example.com";

    private final OAuthServerSettings settings = OAuthDoorsTest.settings(BASE);
    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final OAuthAccessTokenAuthService service = new OAuthAccessTokenAuthService(
            new NarrowedCredentialCheck(
                    new IntegrationScopeRegistry(List.of(new McpProjectScopeProfile(), new McpGlobalScopeProfile()))),
            grants,
            settings,
            new OAuthDoors(settings));

    @BeforeEach
    void setUp() {
        when(grants.isActive(anyString(), anyString(), anyString(), any())).thenReturn(true);
    }

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    private static VanceJwtClaims projectToken(String project, String audience) {
        return VanceJwtClaims.oauth(
                "alice",
                "acme",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                "g1",
                List.of("mcp-project"),
                project,
                audience,
                "dcr_x");
    }

    @Test
    void projectToken_atItsDoor_isAccepted() {
        assertThat(service.isAcceptable(
                        projectToken("docs", BASE + "/brain/acme/mcp/docs"), request("POST", "/brain/acme/mcp/docs")))
                .isTrue();
    }

    /** RFC 8707 audience binding: a token for one door is not a token for another. */
    @Test
    void projectToken_withAudienceOfAnotherProject_isRejected() {
        assertThat(service.isAcceptable(
                        projectToken("docs", BASE + "/brain/acme/mcp/other"), request("POST", "/brain/acme/mcp/docs")))
                .isFalse();
        verify(grants, never()).isActive(anyString(), anyString(), anyString(), any());
    }

    @Test
    void projectToken_outsideItsSurface_isRejected() {
        VanceJwtClaims token = projectToken("docs", BASE + "/brain/acme/mcp/docs");
        assertThat(service.isAcceptable(token, request("POST", "/brain/acme/mcp")))
                .isFalse();
        assertThat(service.isAcceptable(token, request("GET", "/brain/acme/documents")))
                .isFalse();
        assertThat(service.isAcceptable(token, request("GET", "/brain/acme/ws")))
                .isFalse();
    }

    @Test
    void accountToken_isAFullCredential_gatedOnlyByTheGrant() {
        VanceJwtClaims account = VanceJwtClaims.oauth(
                "alice",
                "acme",
                Instant.now(),
                Instant.now().plusSeconds(60),
                "g2",
                List.of(),
                null,
                null,
                "vancetope-foot");

        assertThat(account.isFullAccountCredential()).isTrue();
        assertThat(service.isAcceptable(account, request("GET", "/brain/acme/ws")))
                .isTrue();

        when(grants.isActive("g2", "acme", "alice", null)).thenReturn(false);
        assertThat(service.isAcceptable(account, request("GET", "/brain/acme/documents")))
                .isFalse();
    }

    @Test
    void revokedGrant_rejectsAnotherwiseValidToken() {
        when(grants.isActive("g1", "acme", "alice", "docs")).thenReturn(false);

        assertThat(service.isAcceptable(
                        projectToken("docs", BASE + "/brain/acme/mcp/docs"), request("POST", "/brain/acme/mcp/docs")))
                .isFalse();
    }
}
