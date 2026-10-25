package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.maintenance.UserMaintenanceService;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.sso.SsoConfig;
import de.mhus.vance.shared.sso.SsoConfigService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaKind;
import de.mhus.vance.shared.user.UserQuotaService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The shell refuses at the account quotas like the product surfaces, and
 * steps past them only on {@code --ignore-quota} — so an overshoot is always
 * something the operator chose.
 */
class UserCommandsQuotaTest {

    private final UserService userService = mock(UserService.class);
    private final UserQuotaService quotaService = mock(UserQuotaService.class);
    private final SsoConfigService ssoConfigService = mock(SsoConfigService.class);

    @SuppressWarnings("unchecked")
    private final UserCommands commands = new UserCommands(
            userService,
            mock(PasswordService.class),
            mock(PasswordPolicyService.class),
            mock(UserMaintenanceService.class),
            ssoConfigService,
            mock(AuditService.class),
            quotaService,
            mock(ObjectProvider.class));

    private final UserDocument alice = UserDocument.builder()
            .tenantId("acme")
            .name("alice")
            .status(UserStatus.DISABLED)
            .build();

    @BeforeEach
    void setUp() {
        when(userService.create(anyString(), anyString(), any(), any(), any())).thenReturn(alice);
        when(userService.createServiceAccount(anyString(), anyString(), any(), any(), any()))
                .thenReturn(alice);
        when(userService.update(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(alice);
        when(userService.prepareExternalIdentity(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(alice);
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(alice));
        when(quotaService.status("acme", UserQuotaKind.SEATS)).thenReturn(new UserQuotaService.Usage(6, 5));
        SsoConfig.Provider google = new SsoConfig.Provider("google", "google", "id", "secret", "iss", "disc");
        SsoConfig.DomainRule rule =
                new SsoConfig.DomainRule("acme.com", "acme", "google", false, null, Duration.ofDays(1));
        when(ssoConfigService.config())
                .thenReturn(new SsoConfig(Map.of("google", google), Map.of("acme.com", rule), null));
    }

    private static UserQuotaService.UserQuotaExceededException full(UserQuotaKind kind) {
        return new UserQuotaService.UserQuotaExceededException(kind, 5, 5);
    }

    @Test
    void create_refusesAtTheSeatLimit_beforeThePasswordPrompt() {
        doThrow(full(UserQuotaKind.SEATS)).when(quotaService).checkOneMore("acme", UserQuotaKind.SEATS);

        String out = commands.create("acme", "alice", null, null, null, false, false, false);

        assertThat(out)
                .startsWith("Refused:")
                .contains("5/5")
                .contains("user.quota.seats")
                .contains("--ignore-quota");
        verify(userService, never()).create(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void create_serviceAccount_checksItsOwnQuota() {
        doThrow(full(UserQuotaKind.SERVICE_ACCOUNTS))
                .when(quotaService)
                .checkOneMore("acme", UserQuotaKind.SERVICE_ACCOUNTS);

        String out = commands.create("acme", "_bot", null, null, null, true, true, false);

        assertThat(out).startsWith("Refused:").contains("user.quota.serviceAccounts");
        verify(userService, never()).createServiceAccount(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void create_ignoreQuotaGoesThroughAndShowsTheOvershoot() {
        doThrow(full(UserQuotaKind.SEATS)).when(quotaService).checkOneMore("acme", UserQuotaKind.SEATS);

        String out = commands.create("acme", "alice", null, null, null, true, false, true);

        assertThat(out).startsWith("Created:").contains("6/5 seats (over the limit)");
        verify(quotaService, never()).checkOneMore(anyString(), any());
    }

    @Test
    void update_reEnablingAtTheLimit_isRefused() {
        doThrow(full(UserQuotaKind.SEATS)).when(quotaService).checkStatusChange("acme", alice, UserStatus.ACTIVE);

        String out = commands.update("acme", "alice", null, null, UserStatus.ACTIVE, null, false);

        assertThat(out).startsWith("Refused:");
        verify(userService, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void update_ignoreQuotaReEnables() {
        String out = commands.update("acme", "alice", null, null, UserStatus.ACTIVE, null, true);

        assertThat(out).startsWith("Updated:");
        verify(quotaService, never()).checkStatusChange(anyString(), any(), any());
    }

    @Test
    void update_withoutStatus_isNotChecked() {
        commands.update("acme", "alice", "Alice", null, null, null, false);

        verify(quotaService, never()).checkStatusChange(anyString(), any(), any());
    }

    @Test
    void ssoPrepare_ofAMissingAccount_takesASeat() {
        when(userService.existsByTenantAndName("acme", "bob")).thenReturn(false);
        doThrow(full(UserQuotaKind.SEATS)).when(quotaService).checkOneMore("acme", UserQuotaKind.SEATS);

        String out = commands.ssoPrepare("acme", "bob", "bob@acme.com", false);

        assertThat(out).startsWith("Refused:");
        verify(userService, never())
                .prepareExternalIdentity(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ssoPrepare_ofAnExistingAccount_isNotChecked() {
        when(userService.existsByTenantAndName("acme", "alice")).thenReturn(true);

        String out = commands.ssoPrepare("acme", "alice", "alice@acme.com", false);

        assertThat(out).startsWith("Prepared");
        verify(quotaService, never()).checkOneMore(anyString(), any());
    }
}
