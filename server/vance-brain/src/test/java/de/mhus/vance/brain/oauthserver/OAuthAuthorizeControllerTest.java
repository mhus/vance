package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import de.mhus.vance.shared.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The session end a new grant inherits: a capped session must not be able to
 * mint itself a longer-lived grant through a grant it already has
 * (sso-login.md §6).
 */
class OAuthAuthorizeControllerTest {

    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final OAuthAuthorizeController controller = new OAuthAuthorizeController(
            mock(OAuthAuthorizationFlow.class), mock(TenantService.class), mock(OAuthServerSettings.class), grants);

    @Test
    void accessToken_passesItsSessionEnd() {
        Instant sxp = Instant.now().plusSeconds(3600);
        VanceJwtClaims claims = new VanceJwtClaims(
                "wile",
                "acme",
                Instant.now(),
                null,
                TokenType.ACCESS,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                sxp);

        assertThat(controller.sessionEnd(request(claims))).isEqualTo(sxp);
    }

    @Test
    void accountOAuthToken_isCappedByTheEndOfItsGrant() {
        Instant grantEnd = Instant.now().plusSeconds(7200);
        when(grants.find("acme", "grant-1"))
                .thenReturn(Optional.of(OAuthServerGrantDocument.builder()
                        .grantId("grant-1")
                        .tenantId("acme")
                        .userId("wile")
                        .absoluteExpiresAt(grantEnd)
                        .build()));

        assertThat(controller.sessionEnd(request(oauth("grant-1")))).isEqualTo(grantEnd);
        assertThat(OAuthAuthorizeController.loggedInUser(request(oauth("grant-1"))))
                .isEqualTo("wile");
    }

    @Test
    void accountOAuthToken_ofAnUnknownGrant_endsNow() {
        when(grants.find("acme", "gone")).thenReturn(Optional.empty());

        assertThat(controller.sessionEnd(request(oauth("gone")))).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void noCredential_hasNoSessionEnd() {
        assertThat(controller.sessionEnd(mock(HttpServletRequest.class))).isNull();
    }

    private static VanceJwtClaims oauth(String grantId) {
        return VanceJwtClaims.oauth(
                "wile", "acme", Instant.now(), Instant.now().plusSeconds(300), grantId, List.of(), null, null, "foot");
    }

    private static HttpServletRequest request(VanceJwtClaims claims) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_CLAIMS)).thenReturn(claims);
        return request;
    }
}
