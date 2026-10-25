package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class UserQuotaServiceTest {

    private final UserService userService = mock(UserService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final UserQuotaService service = new UserQuotaService(userService, settingService);

    private void givenLimit(UserQuotaKind kind, int limit) {
        when(settingService.getIntValue(
                        "acme", SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME, kind.settingKey(), 0))
                .thenReturn(limit);
    }

    private static UserDocument user(String name, boolean serviceAccount, UserStatus status) {
        return UserDocument.builder()
                .tenantId("acme")
                .name(name)
                .serviceAccount(serviceAccount)
                .status(status)
                .build();
    }

    @Test
    void settingKeys() {
        assertThat(UserQuotaKind.SEATS.settingKey()).isEqualTo("user.quota.seats");
        assertThat(UserQuotaKind.SERVICE_ACCOUNTS.settingKey()).isEqualTo("user.quota.serviceAccounts");
    }

    @Test
    void noLimit_allowsWithoutCounting() {
        givenLimit(UserQuotaKind.SEATS, 0);

        assertThatCode(() -> service.checkOneMore("acme", UserQuotaKind.SEATS)).doesNotThrowAnyException();
        verify(userService, never()).countCounted(anyString(), any());
    }

    @Test
    void negativeLimit_isNoLimit() {
        givenLimit(UserQuotaKind.SERVICE_ACCOUNTS, -1);

        assertThatCode(() -> service.checkOneMore("acme", UserQuotaKind.SERVICE_ACCOUNTS))
                .doesNotThrowAnyException();
        assertThat(service.status("acme", UserQuotaKind.SERVICE_ACCOUNTS).limit())
                .isZero();
    }

    @Test
    void belowLimit_allows() {
        givenLimit(UserQuotaKind.SEATS, 3);
        when(userService.countCounted("acme", UserQuotaKind.SEATS)).thenReturn(2L);

        assertThatCode(() -> service.checkOneMore("acme", UserQuotaKind.SEATS)).doesNotThrowAnyException();
    }

    @Test
    void atLimit_refusesWithKindCountAndLimit() {
        givenLimit(UserQuotaKind.SERVICE_ACCOUNTS, 2);
        when(userService.countCounted("acme", UserQuotaKind.SERVICE_ACCOUNTS)).thenReturn(2L);

        assertThatThrownBy(() -> service.checkOneMore("acme", UserQuotaKind.SERVICE_ACCOUNTS))
                .isInstanceOfSatisfying(UserQuotaService.UserQuotaExceededException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UserQuotaKind.SERVICE_ACCOUNTS);
                    assertThat(e.active()).isEqualTo(2L);
                    assertThat(e.limit()).isEqualTo(2);
                    assertThat(e.getMessage()).contains("Service-account quota reached: 2 of 2");
                });
    }

    @Test
    void quotasAreIndependent() {
        givenLimit(UserQuotaKind.SEATS, 1);
        givenLimit(UserQuotaKind.SERVICE_ACCOUNTS, 5);
        when(userService.countCounted("acme", UserQuotaKind.SEATS)).thenReturn(1L);
        when(userService.countCounted("acme", UserQuotaKind.SERVICE_ACCOUNTS)).thenReturn(0L);

        assertThatCode(() -> service.checkOneMore("acme", UserQuotaKind.SERVICE_ACCOUNTS))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkOneMore("acme", UserQuotaKind.SEATS))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
    }

    @Test
    void reactivation_isChecked() {
        givenLimit(UserQuotaKind.SEATS, 1);
        when(userService.countCounted("acme", UserQuotaKind.SEATS)).thenReturn(1L);

        assertThatThrownBy(() ->
                        service.checkStatusChange("acme", user("alice", false, UserStatus.DISABLED), UserStatus.ACTIVE))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
        assertThatThrownBy(() -> service.checkStatusChange(
                        "acme", user("alice", false, UserStatus.DISABLED), UserStatus.PENDING))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
    }

    @Test
    void changesThatKeepTheCount_areNotChecked() {
        givenLimit(UserQuotaKind.SEATS, 1);
        when(userService.countCounted("acme", UserQuotaKind.SEATS)).thenReturn(5L);

        assertThatCode(() ->
                        service.checkStatusChange("acme", user("alice", false, UserStatus.PENDING), UserStatus.ACTIVE))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        service.checkStatusChange("acme", user("alice", false, UserStatus.ACTIVE), UserStatus.DISABLED))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.checkStatusChange(
                        "acme", user("alice", false, UserStatus.DISABLED), UserStatus.DISABLED))
                .doesNotThrowAnyException();
    }

    @Test
    void reactivatingVanceInternal_isNeverChecked() {
        givenLimit(UserQuotaKind.SERVICE_ACCOUNTS, 1);
        when(userService.countCounted("acme", UserQuotaKind.SERVICE_ACCOUNTS)).thenReturn(1L);

        assertThatCode(() -> service.checkStatusChange(
                        "acme", user("_vance-admin", true, UserStatus.DISABLED), UserStatus.ACTIVE))
                .doesNotThrowAnyException();
    }

    @Test
    void status_reportsBothQuotas() {
        givenLimit(UserQuotaKind.SEATS, 10);
        givenLimit(UserQuotaKind.SERVICE_ACCOUNTS, 0);
        when(userService.countCounted("acme", UserQuotaKind.SEATS)).thenReturn(11L);
        when(userService.countCounted("acme", UserQuotaKind.SERVICE_ACCOUNTS)).thenReturn(3L);

        UserQuotaService.Status status = service.status("acme");

        assertThat(status.seats()).isEqualTo(new UserQuotaService.Usage(11L, 10));
        assertThat(status.seats().overLimit()).isTrue();
        assertThat(status.serviceAccounts()).isEqualTo(new UserQuotaService.Usage(3L, 0));
        assertThat(status.serviceAccounts().overLimit()).isFalse();
    }
}
