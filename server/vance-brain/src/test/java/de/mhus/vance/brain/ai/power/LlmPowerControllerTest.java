package de.mhus.vance.brain.ai.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.insights.LlmPowerStatusDto;
import de.mhus.vance.api.insights.LlmPowerUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmPowerController}: every endpoint is tenant-admin only — a switch the
 * switched-off user could flip back would be decoration — and an update must
 * name the new position.
 */
class LlmPowerControllerTest {

    private static final String TENANT = "acme";
    private static final Resource TENANT_RESOURCE = new Resource.Tenant(TENANT);

    private final LlmPowerSwitch powerSwitch = mock(LlmPowerSwitch.class);
    private final UserService userService = mock(UserService.class);
    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final LlmPowerController controller = new LlmPowerController(powerSwitch, userService, authority);

    @Test
    void nonAdmin_isRefused_onEveryEndpoint_beforeAnythingIsReadOrWritten() {
        doThrow(new PermissionDeniedException(
                        SecurityContext.user("wile.coyote", TENANT, List.of()), TENANT_RESOURCE, Action.ADMIN))
                .when(authority)
                .enforce(request, TENANT_RESOURCE, Action.ADMIN);
        LlmPowerUpdateRequest on = new LlmPowerUpdateRequest(true);

        assertThatThrownBy(() -> controller.status(TENANT, request)).isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> controller.setTenant(TENANT, on, request))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> controller.setUser(TENANT, "wile.coyote", on, request))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(powerSwitch, userService);
    }

    @Test
    void admin_switchesAUser_asThemselves() {
        when(authority.contextOf(request)).thenReturn(SecurityContext.user("marvin", TENANT, List.of()));
        when(powerSwitch.setUserPower(TENANT, "wile.coyote", false, "marvin")).thenReturn(LlmPowerState.ALL_ON);
        when(powerSwitch.now()).thenReturn(Instant.EPOCH);
        when(powerSwitch.verifyAt()).thenReturn(Instant.EPOCH);
        when(userService.all(TENANT)).thenReturn(List.of());

        LlmPowerStatusDto status = controller.setUser(TENANT, "wile.coyote", new LlmPowerUpdateRequest(false), request);

        assertThat(status.isEnabled()).isTrue();
        verify(authority).enforce(request, TENANT_RESOURCE, Action.ADMIN);
        verify(powerSwitch).setUserPower(TENANT, "wile.coyote", false, "marvin");
        verify(powerSwitch, never()).setTenantPower(anyString(), anyBoolean(), anyString());
    }

    @Test
    void updateWithoutEnabled_isInvalid_neverAnImplicitOff() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new LlmPowerUpdateRequest())).isNotEmpty();
            assertThat(validator.validate(new LlmPowerUpdateRequest(false))).isEmpty();
        }
    }
}
