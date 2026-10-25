package de.mhus.vance.brain.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.users.UserCreateRequest;
import de.mhus.vance.api.users.UserUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaKind;
import de.mhus.vance.shared.user.UserQuotaService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The two places the admin REST makes an account counted: create, and re-enabling a DISABLED one. */
class UserAdminControllerQuotaTest {

    private UserService users;
    private UserQuotaService quota;
    private UserAdminController controller;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        quota = mock(UserQuotaService.class);
        controller = new UserAdminController(
                users,
                mock(PasswordService.class),
                mock(PasswordPolicyService.class),
                mock(RequestAuthority.class),
                quota);
        http = mock(HttpServletRequest.class);
        when(http.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("admin");
    }

    private static UserQuotaService.UserQuotaExceededException full() {
        return new UserQuotaService.UserQuotaExceededException(UserQuotaKind.SEATS, 2, 2);
    }

    @Test
    void create_atTheSeatLimit_createsNothing() {
        doThrow(full()).when(quota).checkOneMore("acme", UserQuotaKind.SEATS);
        UserCreateRequest request = UserCreateRequest.builder().name("alice").build();

        assertThatThrownBy(() -> controller.create("acme", request, http))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
        verify(users, never()).create(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void reEnabling_atTheLimit_changesNothing() {
        UserDocument disabled = UserDocument.builder()
                .tenantId("acme")
                .name("alice")
                .status(UserStatus.DISABLED)
                .build();
        when(users.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(disabled));
        doThrow(full()).when(quota).checkStatusChange("acme", disabled, UserStatus.ACTIVE);
        UserUpdateRequest request = UserUpdateRequest.builder().status("ACTIVE").build();

        assertThatThrownBy(() -> controller.update("acme", "alice", request, http))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
        verify(users, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void anUpdateWithoutStatus_isNotChecked() {
        UserUpdateRequest request = UserUpdateRequest.builder().title("Alice").build();
        when(users.update("acme", "alice", "Alice", null, null, null))
                .thenReturn(UserDocument.builder().name("alice").build());

        controller.update("acme", "alice", request, http);

        verify(quota, never()).checkStatusChange(anyString(), any(), any());
    }
}
