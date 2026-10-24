package de.mhus.vance.brain.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.home.HomeStatusDto;
import de.mhus.vance.api.me.MeDto;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.home.HomeStatusService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.profile.UiSettingsService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One answer about the caller: identity, roles, settings, status. */
class MeControllerTest {

    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of());

    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final UserService userService = mock(UserService.class);
    private final UiSettingsService uiSettings = mock(UiSettingsService.class);
    private final HomeStatusService homeStatus = mock(HomeStatusService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final MeController controller =
            new MeController(authority, permissionService, userService, uiSettings, homeStatus);

    @BeforeEach
    void setUp() {
        when(authority.contextOf(request)).thenReturn(ALICE);
        when(userService.findByTenantAndName("acme", "alice"))
                .thenReturn(Optional.of(UserDocument.builder()
                        .tenantId("acme")
                        .name("alice")
                        .title("Alice A.")
                        .build()));
        when(permissionService.tenantRoleOf(ALICE)).thenReturn(TenantRole.CREATOR);
        when(uiSettings.load("acme", "alice")).thenReturn(Map.of("webui.theme", "dark"));
        when(homeStatus.status(any(), any(), any()))
                .thenReturn(HomeStatusDto.builder().llmPowerOn(true).build());
    }

    @Test
    void me_carriesIdentityRoleSettingsAndStatus() {
        MeDto me = controller.me("acme", null, request);

        assertThat(me.getUsername()).isEqualTo("alice");
        assertThat(me.getTenantId()).isEqualTo("acme");
        assertThat(me.getDisplayName()).isEqualTo("Alice A.");
        assertThat(me.getTenantRole()).isEqualTo(TenantRole.CREATOR);
        assertThat(me.getSettings()).containsEntry("webui.theme", "dark");
        assertThat(me.getStatus().getLlmPowerOn()).isTrue();
        assertThat(me.getProject()).isNull();
        assertThat(me.getProjectRole()).isNull();
        verify(permissionService, never()).projectRoleOf(any(), any());
    }

    @Test
    void me_withProject_reportsTheOwnMembershipAndTheProjectStatus() {
        when(permissionService.projectRoleOf(ALICE, "rocket")).thenReturn(ProjectRole.MEMBER);

        MeDto me = controller.me("acme", "rocket", request);

        assertThat(me.getProject()).isEqualTo("rocket");
        assertThat(me.getProjectRole()).isEqualTo(ProjectRole.MEMBER);
        verify(homeStatus).status(ALICE, "acme", "rocket");
    }

    @Test
    void me_survivesAFailingStatus() {
        when(homeStatus.status(any(), any(), any())).thenThrow(new IllegalStateException("mongo blip"));

        MeDto me = controller.me("acme", null, request);

        assertThat(me.getStatus()).isNull();
        assertThat(me.getTenantRole()).isEqualTo(TenantRole.CREATOR);
    }
}
