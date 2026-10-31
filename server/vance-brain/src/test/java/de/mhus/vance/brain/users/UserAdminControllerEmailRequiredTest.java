package de.mhus.vance.brain.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.users.UserCreateRequest;
import de.mhus.vance.api.users.UserUpdateRequest;
import de.mhus.vance.brain.access.TestLoginModes;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.password.PasswordPolicyService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** In e-mail code mode an account without an address could never sign in, so the admin cannot create or leave one. */
class UserAdminControllerEmailRequiredTest {

    private UserService users;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        http = mock(HttpServletRequest.class);
        when(http.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("admin");
    }

    @Test
    void creatingWithoutAnAddressIsRefusedInEmailCodeMode() {
        UserCreateRequest request = UserCreateRequest.builder().name("alice").build();

        assertThatThrownBy(() -> controller(true).create("acme", request, http))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(users, never()).create(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void creatingWithAnAddressIsFineInEmailCodeMode() {
        when(users.create("acme", "alice", null, null, "alice@example.com"))
                .thenReturn(
                        UserDocument.builder().tenantId("acme").name("alice").build());
        UserCreateRequest request = UserCreateRequest.builder()
                .name("alice")
                .email("alice@example.com")
                .build();

        assertThat(controller(true).create("acme", request, http).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void clearingTheAddressIsRefusedInEmailCodeMode() {
        UserUpdateRequest request = UserUpdateRequest.builder().email(" ").build();

        assertThatThrownBy(() -> controller(true).update("acme", "alice", request, http))
                .isInstanceOf(ResponseStatusException.class);

        verify(users, never()).update(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void withoutEmailCodeModeNothingChanges() {
        when(users.create("acme", "alice", null, null, null))
                .thenReturn(
                        UserDocument.builder().tenantId("acme").name("alice").build());

        assertThat(controller(false)
                        .create(
                                "acme",
                                UserCreateRequest.builder().name("alice").build(),
                                http)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private UserAdminController controller(boolean emailToken) {
        return new UserAdminController(
                users,
                mock(PasswordService.class),
                mock(PasswordPolicyService.class),
                mock(RequestAuthority.class),
                TestLoginModes.of(emailToken, false));
    }
}
