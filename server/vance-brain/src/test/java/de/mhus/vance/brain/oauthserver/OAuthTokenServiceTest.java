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
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.oauthserver.OAuthServerCodeDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.time.Instant;
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
    private final OAuthServerClientService clientService = mock(OAuthServerClientService.class);
    private final OAuthTokenService service = new OAuthTokenService(
            settings, authorizations, grants, jwtService, userService, new OAuthDoors(settings), clientService);

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
        when(authorizations.consumeCode("acme", "CODE")).thenReturn(true);
        UserDocument user = new UserDocument();
        user.setStatus(UserStatus.ACTIVE);
        user.setLoginEnabled(true);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(user));
        when(jwtService.createOAuthAccessToken(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("jwt");
    }

    private void codeIs(OAuthServerCodeDocument code) {
        when(authorizations.findCode("acme", "CODE")).thenReturn(Optional.of(code));
    }

    private static OAuthServerCodeDocument code() {
        return OAuthServerCodeDocument.builder()
                .tenantId("acme")
                .grantId("g1")
                .clientId("dcr_x")
                .redirectUri(REDIRECT)
                .codeChallenge(CHALLENGE)
                .resource(RESOURCE)
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static void assertInvalidGrant(Throwable e) {
        assertThat(e).isInstanceOf(OAuthTokenService.OAuthTokenError.class);
        OAuthTokenService.OAuthTokenError error = (OAuthTokenService.OAuthTokenError) e;
        assertThat(error.error()).isEqualTo("invalid_grant");
        // Generic on purpose: which check failed is for the log, not the caller.
        assertThat(error.getMessage()).isEqualTo(OAuthTokenService.INVALID_GRANT_DESCRIPTION);
    }

    @Test
    void pkceS256_matchesTheRfc7636Example() {
        assertThat(OAuthTokenService.pkceS256(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void redeem_validCode_returnsTokensBoundToTheGrant_andSpendsTheCode() {
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
        verify(authorizations).consumeCode("acme", "CODE");
        verify(clientService).touch("dcr_x");
    }

    /** A failed binding check must not spend the code — the rightful client can still redeem it. */
    @Test
    void redeem_wrongVerifier_isInvalidGrant_andLeavesTheCodeAlone() {
        codeIs(code());

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", "x".repeat(43), RESOURCE))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(authorizations, never()).consumeCode(anyString(), anyString());
        verify(grants, never()).revokeAfterReplay(anyString(), anyString(), anyString());
    }

    @Test
    void redeem_otherClientOrRedirect_isInvalidGrant_andLeavesTheCodeAlone() {
        codeIs(code());
        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_other", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", "http://127.0.0.1:1/x", "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(authorizations, never()).consumeCode(anyString(), anyString());
    }

    @Test
    void redeem_unknownCode_isInvalidGrant() {
        when(authorizations.findCode("acme", "CODE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
    }

    @Test
    void redeem_expiredCode_isInvalidGrant_withoutRevocation() {
        OAuthServerCodeDocument expired = code();
        codeIs(expired);
        when(authorizations.isExpired(expired)).thenReturn(true);

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants, never()).revokeAfterReplay(anyString(), anyString(), anyString());
        verify(authorizations, never()).consumeCode(anyString(), anyString());
    }

    @Test
    void redeem_resourceOfAnotherDoor_isInvalidTarget_andLeavesTheCodeAlone() {
        codeIs(code());

        assertThatThrownBy(
                        () -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, BASE + "/brain/acme/mcp"))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class)
                .satisfies(e -> assertThat(((OAuthTokenService.OAuthTokenError) e).error())
                        .isEqualTo("invalid_target"));
        verify(authorizations, never()).consumeCode(anyString(), anyString());
    }

    /** OAuth 2.1 §4.1.3 — a used code presented again by its rightful client revokes what it produced. */
    @Test
    void redeem_replayedCode_withValidBinding_revokesTheGrant() {
        OAuthServerCodeDocument used = code();
        used.setUsedAt(Instant.now().minusSeconds(5));
        codeIs(used);

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants).revokeAfterReplay("acme", "g1", "code");
    }

    /** A stranger with a used code but without the verifier cannot get the grant revoked. */
    @Test
    void redeem_replayedCode_withWrongVerifier_revokesNothing() {
        OAuthServerCodeDocument used = code();
        used.setUsedAt(Instant.now().minusSeconds(5));
        codeIs(used);

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", "y".repeat(43), null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_other", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants, never()).revokeAfterReplay(anyString(), anyString(), anyString());
    }

    /** Two redemptions racing: the loser saw an unused code, but the stamp went to the other — a replay. */
    @Test
    void redeem_lostTheConsumeRace_toAnotherRedemption_revokesTheGrant() {
        OAuthServerCodeDocument fresh = code();
        OAuthServerCodeDocument spent = code();
        spent.setUsedAt(Instant.now());
        when(authorizations.findCode("acme", "CODE"))
                .thenReturn(Optional.of(fresh))
                .thenReturn(Optional.of(spent));
        when(authorizations.consumeCode("acme", "CODE")).thenReturn(false);

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants).revokeAfterReplay("acme", "g1", "code");
        verify(grants, never()).startRefreshStrand(anyString(), any());
    }

    @Test
    void redeem_disabledAccount_isInvalidGrant() {
        codeIs(code());
        UserDocument disabled = new UserDocument();
        disabled.setStatus(UserStatus.ACTIVE);
        disabled.setLoginEnabled(false);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.redeemCode("acme", "CODE", REDIRECT, "dcr_x", VERIFIER, null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants, never()).startRefreshStrand(anyString(), any());
    }

    // ──────────────────── refresh ────────────────────

    private void inspectionFinds(String token) {
        when(grants.inspectRefreshToken("acme", token, "dcr_x"))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(grant, null, null));
    }

    @Test
    void refresh_replay_isInvalidGrant_andRotatesNothing() {
        when(grants.inspectRefreshToken("acme", "g1.old", "dcr_x"))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(
                        null, null, OAuthServerGrantService.RefreshFailure.REPLAYED));

        assertThatThrownBy(() -> service.refresh("acme", "g1.old", "dcr_x", null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants, never()).rotateRefreshToken(anyString(), anyString(), anyString(), any());
    }

    @Test
    void refresh_ok_mintsANewAccessToken() {
        inspectionFinds("g1.live");
        when(grants.rotateRefreshToken(eq("acme"), eq("g1.live"), eq("dcr_x"), any()))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(grant, "g1.next", null));

        Map<String, Object> out = service.refresh("acme", "g1.live", "dcr_x", RESOURCE);

        assertThat(out).containsEntry("refresh_token", "g1.next").containsEntry("access_token", "jwt");
    }

    /** A wrong resource is refused before rotation — the client's token stays valid. */
    @Test
    void refresh_wrongResource_isInvalidTarget_andDoesNotSpendTheToken() {
        inspectionFinds("g1.live");

        assertThatThrownBy(() -> service.refresh("acme", "g1.live", "dcr_x", BASE + "/brain/acme/mcp"))
                .isInstanceOf(OAuthTokenService.OAuthTokenError.class)
                .satisfies(e -> assertThat(((OAuthTokenService.OAuthTokenError) e).error())
                        .isEqualTo("invalid_target"));
        verify(grants, never()).rotateRefreshToken(anyString(), anyString(), anyString(), any());
    }

    @Test
    void refresh_disabledAccount_isInvalidGrant_andDoesNotSpendTheToken() {
        inspectionFinds("g1.live");
        UserDocument disabled = new UserDocument();
        disabled.setStatus(UserStatus.DISABLED);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.refresh("acme", "g1.live", "dcr_x", null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
        verify(grants, never()).rotateRefreshToken(anyString(), anyString(), anyString(), any());
    }

    @Test
    void refresh_lostRotation_isInvalidGrant() {
        inspectionFinds("g1.live");
        when(grants.rotateRefreshToken(eq("acme"), eq("g1.live"), eq("dcr_x"), any()))
                .thenReturn(new OAuthServerGrantService.RefreshOutcome(
                        null, null, OAuthServerGrantService.RefreshFailure.INVALID));

        assertThatThrownBy(() -> service.refresh("acme", "g1.live", "dcr_x", null))
                .satisfies(OAuthTokenServiceTest::assertInvalidGrant);
    }

    // ──────────────────── revocation ────────────────────

    @Test
    void revoke_refreshToken_passesTheClientOn() {
        service.revoke("acme", "g1.live", "dcr_x");

        verify(grants).revokeByRefreshToken("acme", "g1.live", "dcr_x");
    }
}
