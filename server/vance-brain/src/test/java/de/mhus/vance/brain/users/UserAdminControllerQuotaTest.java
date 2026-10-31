package de.mhus.vance.brain.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.users.UserCreateRequest;
import de.mhus.vance.api.users.UserUpdateRequest;
import de.mhus.vance.brain.access.TestLoginModes;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserQuotaKind;
import de.mhus.vance.shared.user.UserQuotaService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin REST goes through the enforcing {@link UserService} methods, and
 * the service's refusal reaches the caller unchanged — 409 by
 * {@link UserQuotaExceptionAdvice}.
 */
class UserAdminControllerQuotaTest {

    private UserService users;
    private UserAdminController controller;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        controller = new UserAdminController(
                users,
                mock(PasswordService.class),
                mock(PasswordPolicyService.class),
                mock(RequestAuthority.class),
                TestLoginModes.of(false, false));
        http = mock(HttpServletRequest.class);
        when(http.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("admin");
    }

    private static UserQuotaService.UserQuotaExceededException full() {
        return new UserQuotaService.UserQuotaExceededException(UserQuotaKind.SEATS, 2, 2);
    }

    @Test
    void create_refusalOfTheServicePropagates() {
        when(users.create("acme", "alice", null, null, null)).thenThrow(full());
        UserCreateRequest request = UserCreateRequest.builder().name("alice").build();

        assertThatThrownBy(() -> controller.create("acme", request, http))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
    }

    @Test
    void reEnabling_refusalOfTheServicePropagates() {
        when(users.update("acme", "alice", null, null, UserStatus.ACTIVE, null)).thenThrow(full());
        UserUpdateRequest request = UserUpdateRequest.builder().status("ACTIVE").build();

        assertThatThrownBy(() -> controller.update("acme", "alice", request, http))
                .isInstanceOf(UserQuotaService.UserQuotaExceededException.class);
    }
}
