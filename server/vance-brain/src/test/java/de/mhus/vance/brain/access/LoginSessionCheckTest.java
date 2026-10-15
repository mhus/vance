package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.user.ExternalIdentity;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A session started with the password must not survive the account's switch
 * to SSO — neither at the filter nor by refreshing (sso-login.md §6).
 */
class LoginSessionCheckTest {

    private static final Instant PREPARED_AT = Instant.parse("2026-10-16T10:00:00.700Z");

    private final UserService users = mock(UserService.class);
    private final LoginSessionCheck check = new LoginSessionCheck(users, 30);

    @Test
    void accessToken_issuedBeforeTheFloor_isRejected_afterIt_accepted() {
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(account(PREPARED_AT, true)));

        assertThat(check.admitsAccessToken(token(PREPARED_AT.minusSeconds(3600), null)))
                .isFalse();
        assertThat(check.admitsAccessToken(token(PREPARED_AT.plusSeconds(5), null)))
                .isTrue();
    }

    @Test
    void accessToken_fromTheFloorsOwnSecond_isRejected() {
        // iat has whole seconds: 10:00:00 is before 10:00:00.700.
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(account(PREPARED_AT, true)));

        assertThat(check.admitsAccessToken(token(Instant.parse("2026-10-16T10:00:00Z"), null)))
                .isFalse();
    }

    @Test
    void accessToken_withoutIssuedAt_isRejected_onlyWhenThereIsAFloor() {
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(account(PREPARED_AT, true)));
        assertThat(check.admitsAccessToken(token(null, null))).isFalse();

        LoginSessionCheck fresh = new LoginSessionCheck(users, 30);
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(account(null, false)));
        assertThat(fresh.admitsAccessToken(token(null, null))).isTrue();
    }

    @Test
    void accountState_isCachedPerAccount() {
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(account(null, false)));

        check.admitsAccessToken(token(Instant.now(), null));
        check.admitsAccessToken(token(Instant.now(), null));

        verify(users, times(1)).findByTenantAndName("acme", "wile");
    }

    @Test
    void mayRenew_ssoAccount_onlyACappedSessionAboveTheFloor() {
        UserDocument sso = account(PREPARED_AT, true);
        Instant after = PREPARED_AT.plusSeconds(60);

        assertThat(LoginSessionCheck.mayRenew(sso, token(after, after.plusSeconds(3600))))
                .isTrue();
        // An uncapped session never renews on an SSO account.
        assertThat(LoginSessionCheck.mayRenew(sso, token(after, null))).isFalse();
        // The password session from before the switch is over.
        assertThat(LoginSessionCheck.mayRenew(sso, token(PREPARED_AT.minusSeconds(60), null)))
                .isFalse();
    }

    @Test
    void mayRenew_afterUnlink_theOldPasswordSessionStaysOver() {
        // Unlinking removes the identity, never the floor.
        UserDocument unlinked = account(PREPARED_AT, false);

        assertThat(LoginSessionCheck.mayRenew(unlinked, token(PREPARED_AT.minusSeconds(60), null)))
                .isFalse();
        assertThat(LoginSessionCheck.mayRenew(unlinked, token(PREPARED_AT.plusSeconds(60), null)))
                .isTrue();
    }

    @Test
    void mayRenew_passwordAccount_isUnaffected() {
        assertThat(LoginSessionCheck.mayRenew(account(null, false), token(Instant.now(), null)))
                .isTrue();
    }

    private static UserDocument account(@Nullable Instant tokensNotBefore, boolean external) {
        return UserDocument.builder()
                .tenantId("acme")
                .name("wile")
                .tokensNotBefore(tokensNotBefore)
                .externalIdentity(
                        external
                                ? ExternalIdentity.builder()
                                        .provider("google")
                                        .issuer(ExternalIdentity.GOOGLE_ISSUER)
                                        .subject("sub-1")
                                        .build()
                                : null)
                .build();
    }

    private static VanceJwtClaims token(@Nullable Instant issuedAt, @Nullable Instant sessionExpiresAt) {
        return new VanceJwtClaims(
                "wile",
                "acme",
                issuedAt,
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
                sessionExpiresAt);
    }
}
