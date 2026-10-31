package de.mhus.vance.brain.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.EmailTokenStartResponse;
import de.mhus.vance.api.profile.ProfileDto;
import de.mhus.vance.api.profile.ProfileEmailChangeConfirmRequest;
import de.mhus.vance.api.profile.ProfileEmailChangeRequest;
import de.mhus.vance.api.profile.ProfileUpdateRequest;
import de.mhus.vance.brain.access.TestLoginModes;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.EmailLoginService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ProfileControllerEmailChangeTest {

    private static final String TENANT = "acme";
    private static final String USER = "alice";

    private UserService users;
    private EmailLoginService emailLogin;
    private HttpServletRequest http;
    private UserDocument alice;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        emailLogin = mock(EmailLoginService.class);
        http = mock(HttpServletRequest.class);
        when(http.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(USER);
        alice = UserDocument.builder()
                .tenantId(TENANT)
                .name(USER)
                .email("alice@example.com")
                .status(UserStatus.ACTIVE)
                .build();
        when(users.findByTenantAndName(TENANT, USER)).thenAnswer(inv -> Optional.of(alice));
        when(users.update(anyString(), anyString(), any(), any(), any(), any())).thenReturn(alice);
    }

    @Test
    void aPlainWriteOfANewAddressIsRefusedInEmailCodeMode() {
        ProfileUpdateRequest request =
                ProfileUpdateRequest.builder().email("evil@example.com").build();

        assertThatThrownBy(() -> controller(true).update(TENANT, request, http))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(users, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void clearingTheAddressIsRefusedInEmailCodeMode() {
        ProfileUpdateRequest request = ProfileUpdateRequest.builder().email("").build();

        assertThatThrownBy(() -> controller(true).update(TENANT, request, http))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void theTitleStillChangesAndTheSameAddressIsIgnoredInEmailCodeMode() {
        ProfileUpdateRequest request = ProfileUpdateRequest.builder()
                .title("Alice")
                .email("ALICE@example.com")
                .build();

        ResponseEntity<ProfileDto> resp = controller(true).update(TENANT, request, http);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(users).update(TENANT, USER, "Alice", null, null, null);
    }

    @Test
    void withoutEmailCodeModeTheAddressIsWrittenDirectly() {
        ProfileUpdateRequest request =
                ProfileUpdateRequest.builder().email("new@example.com").build();

        controller(false).update(TENANT, request, http);

        verify(users).update(TENANT, USER, null, "new@example.com", null, null);
    }

    @Test
    void startingAChangeMailsTheNewAddress() {
        Instant expires = Instant.parse("2026-10-30T12:10:00Z");
        when(emailLogin.startEmailChange(TENANT, USER, "new@example.com"))
                .thenReturn(new EmailLoginService.Started("tx-1", expires));

        ResponseEntity<EmailTokenStartResponse> resp = controller(true)
                .startEmailChange(
                        TENANT,
                        ProfileEmailChangeRequest.builder()
                                .email(" new@example.com ")
                                .build(),
                        http);

        assertThat(resp.getBody().getTransactionId()).isEqualTo("tx-1");
        assertThat(resp.getBody().getExpiresAtTimestamp()).isEqualTo(expires.toEpochMilli());
    }

    @Test
    void startingAChangeToTheCurrentAddressIsRefused() {
        assertThatThrownBy(() -> controller(true)
                        .startEmailChange(
                                TENANT,
                                ProfileEmailChangeRequest.builder()
                                        .email("Alice@Example.com")
                                        .build(),
                                http))
                .isInstanceOf(ResponseStatusException.class);

        verify(emailLogin, never()).startEmailChange(anyString(), anyString(), anyString());
    }

    @Test
    void theChangeEndpointsAreNotFoundOutsideEmailCodeMode() {
        ProfileController off = controller(false);

        assertThat(off.startEmailChange(
                                TENANT,
                                ProfileEmailChangeRequest.builder()
                                        .email("a@b.de")
                                        .build(),
                                http)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(off.confirmEmailChange(
                                TENANT,
                                ProfileEmailChangeConfirmRequest.builder()
                                        .transactionId("tx")
                                        .token("123456789012")
                                        .build(),
                                http)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aGoodCodeConfirmsTheChange() {
        when(emailLogin.confirmEmailChange(TENANT, USER, "tx-1", "123456789012"))
                .thenReturn(Optional.of("new@example.com"));

        ResponseEntity<ProfileDto> resp = controller(true)
                .confirmEmailChange(
                        TENANT,
                        ProfileEmailChangeConfirmRequest.builder()
                                .transactionId("tx-1")
                                .token("123456789012")
                                .build(),
                        http);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aWrongCodeIsABadRequest() {
        when(emailLogin.confirmEmailChange(TENANT, USER, "tx-1", "000000000000"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller(true)
                        .confirmEmailChange(
                                TENANT,
                                ProfileEmailChangeConfirmRequest.builder()
                                        .transactionId("tx-1")
                                        .token("000000000000")
                                        .build(),
                                http))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private ProfileController controller(boolean emailToken) {
        return new ProfileController(
                users,
                mock(SettingService.class),
                mock(TeamService.class),
                mock(UiSettingsService.class),
                mock(PasswordService.class),
                mock(PasswordPolicyService.class),
                emailLogin,
                TestLoginModes.of(emailToken, false));
    }
}
