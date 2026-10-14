package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthorizationService;
import de.mhus.vance.shared.oauthserver.OAuthServerCodeDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthTokenServiceTest {

    private static final String BASE = "https://vance.example.com";
    private static final String RESOURCE = BASE + "/brain/acme/mcp/docs";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String REDIRECT = "http://127.0.0.1:4711/callback";

    private final OAuthServerSettings settings = OAuthDoorsTest.settings(BASE);
    private final OAuthServerAuthorizationService authorizations = mock(OAuthServerAuthorizationService.class);
    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final UserService userService = mock(UserService.class);
    private final OAuthTokenService service =
            new OAuthTokenService(settings, authorizations, grants, jwtService, userService, new OAuthDoors(settings));

    private OAuthServerGrantDocument grant;

    @BeforeEach
    void setUp() {
        grant = OAuthServerGrantDocument.builder()
                .grantId("g1")
                .tenantId("acme")
                .userId("alice")
                .clientId("dcr_x")
                .resource(RESOURCE)
                .scope("mcp-project")
                .scopeProfiles(List.of("mcp-project"))
                .projectId("docs")
                .build();
        when(grants.find("acme", "g1")).thenReturn(Optional.of(grant));
        when(grants.startRefreshStrand(eq("g1"), any())).thenReturn("g1.refresh");
        UserDocument user = new UserDocument();
        user.setStatus(UserStatus.ACTIVE);
        user.setLoginEnabled(true);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(user));
        when(jwtService.createOAuthAccessToken(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("jwt");
    }

    private void codeIs(OAuthServerCodeDocument code) {
        when(authorizations.redeemCode("acme", "CODE"))
                .thenReturn(new OAuthServerAuthorizationService.Redemption(code, null));
    }

    private static OAuthServerCodeDocument code() {
        return OAuthServerCodeDocument.builder()
                .grantId("g1")
                .clientId("dcr_x")
                .redirectUri(REDIRECT)
                .codeChallenge(CHALLENGE)
                .resource(RESOURCE)
                .build();
    }

    @Test
    void pkceS256_matchesTheRfc7636Example() {
        assertThat(OAuthTokenService.pkceS256(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void redeem_validCode_returnsTokensBoundToTheGrant() {
        codeIs(code());

        Map<String, Object> out = service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, RESOURCE);

        assertThat(out)
                .containsEntry("access_token", "jwt")
                .containsEntry("token_type", "Bearer")
                .containsEntry("refresh_token", "g1.refresh")
                .containsEntry("scope", "mcp-project")
                .containsEntry("expires_in", 3600L);
        verify(jwtService)
                .createOAuthAccessToken(
                        eq("acme"),
                        eq("alice"),
                        eq("g1"),
                        eq("dcr_x"),
                        eq(List.of("mcp-project")),
                        eq("docs"),
                        eq(RESOURCE),
                        any());
    }

    @Test
    void redeem_wrongVerifier_isInvalidGrant() {
        codeIs(code());

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", "x".repeat(43), RESOURCE))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class)
                .hasMessageContaining("PKCE");
    }

    @Test
    void redeem_otherClientOrRedirect_isInvalidGrant() {
        codeIs(code());
        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_other", VERIFIER, null))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class);
        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", "http://127.0.0.1:1/x", "dcr_x", VERIFIER, null))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class);
    }

    @Test
    void redeem_resourceOfAnotherDoor_isInvalidTarget() {
        codeIs(code());

        assertThatThrownBy(
                        () -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, BASE + "/brain/acme/mcp"))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class)
                .satisfies(e -> assertThat(((OAuthTokenService.OAuthTokenError) e).error())
                        .isEqualTo("invalid_target"));
    }

    /** OAuth 2.1 §4.1.3 — a replayed code revokes what it produced. */
    @Test
    void redeem_replayedCode_revokesTheGrant() {
        when(authorizations.redeemCode("acme", "CODE"))
                .thenReturn(new OAuthServerAuthorizationService.Redemption(null, "g1"));

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class);
        verify(grants).revoke("acme", "g1", "code-replay");
    }

    @Test
    void redeem_disabledAccount_isInvalidGrant() {
        codeIs(code());
        UserDocument disabled = new UserDocument();
        disabled.setStatus(UserStatus.ACTIVE);
        disabled.setLoginEnabled(false);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class);
        verify(grants, never()).startRefreshStrand(anyString(), any());
    }

    @Test
    void refresh_replay_reportsAndKeepsNoToken() {
        when(grants.rotateRefreshToken(eq("acme"), eq("g1.old"), eq("dcr_x"), any()))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(
                        null, null, OAuthServerGrantService.RefreshFailure.REPLAYED));

        assertThatThrownBy(() -> service.refresh("acme", "g1.old", "dcr_x", null))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void refresh_ok_mintsANewAccessToken() {
        when(grants.rotateRefreshToken(eq("acme"), eq("g1.live"), eq("dcr_x"), any()))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(grant, "g1.next", null));

        Map<String, Object> out = service.refresh("acme", "g1.live", "dcr_x", RESOURCE);

        assertThat(out).containsEntry("refresh_token", "g1.next").containsEntry("access_token", "jwt");
    }
}
