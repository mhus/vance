package de.mhus.vance.shared.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The assignment rules: nobody above themselves, the last tenant admin, persons only, existence. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleAdministrationServiceTest {

    private static final SecurityContext ADMIN = SecurityContext.user("adm", "acme", List.of());

    @Mock
    RoleAssignmentService assignments;

    @Mock
    PermissionService permissionService;

    @Mock
    UserService userService;

    @Mock
    TeamService teamService;

    @Mock
    ProjectService projectService;

    @InjectMocks
    RoleAdministrationService administration;

    @BeforeEach
    void setUp() {
        when(permissionService.storesTenantRoles()).thenReturn(true);
        when(permissionService.tenantRoleOf(ADMIN)).thenReturn(TenantRole.ADMIN);
        when(userService.findByTenantAndName(any(), any()))
                .thenAnswer(inv -> Optional.of(UserDocument.builder()
                        .tenantId("acme")
                        .name(inv.getArgument(1))
                        .build()));
        when(assignments.tenantRoleOf(any(), any())).thenReturn(TenantRole.USER);
    }

    @Test
    void admin_promotesUpToTheirOwnRole() {
        administration.assignTenantRole(ADMIN, "acme", "bob", TenantRole.ADMIN);

        verify(assignments).setTenantRole("acme", "bob", TenantRole.ADMIN, "adm");
    }

    @Test
    void admin_cannotAssignAboveTheirOwnRole() {
        assertThatThrownBy(() -> administration.assignTenantRole(ADMIN, "acme", "bob", TenantRole.TENANT_ADMIN))
                .isInstanceOfSatisfying(
                        RoleAssignmentException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(RoleAssignmentException.Kind.BEYOND_AUTHORITY));
        verify(assignments, never()).setTenantRole(any(), any(), any(), any());
    }

    @Test
    void admin_cannotDemoteSomeoneAboveThem() {
        when(assignments.tenantRoleOf("acme", "root")).thenReturn(TenantRole.TENANT_ADMIN);

        assertThatThrownBy(() -> administration.assignTenantRole(ADMIN, "acme", "root", TenantRole.USER))
                .isInstanceOfSatisfying(
                        RoleAssignmentException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(RoleAssignmentException.Kind.BEYOND_AUTHORITY));
    }

    @Test
    void lastTenantAdmin_cannotBeDemoted_evenByTheOperator() {
        when(permissionService.tenantRoleOf(SecurityContext.SYSTEM)).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.tenantRoleOf("acme", "root")).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.usersWithTenantRole("acme", TenantRole.TENANT_ADMIN)).thenReturn(List.of("root"));

        assertThatThrownBy(
                        () -> administration.assignTenantRole(SecurityContext.SYSTEM, "acme", "root", TenantRole.ADMIN))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("last TENANT_ADMIN");
    }

    @Test
    void lastTenantAdmin_isProtected_evenWhenTheOperatorAccountIsOne() {
        when(permissionService.tenantRoleOf(SecurityContext.SYSTEM)).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.tenantRoleOf("acme", "root")).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.usersWithTenantRole("acme", TenantRole.TENANT_ADMIN))
                .thenReturn(List.of("_vance-admin", "root"));

        assertThatThrownBy(
                        () -> administration.assignTenantRole(SecurityContext.SYSTEM, "acme", "root", TenantRole.USER))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("last TENANT_ADMIN");
    }

    @Test
    void tenantAdmin_canBeDemoted_whenAnotherOneExists() {
        when(permissionService.tenantRoleOf(SecurityContext.SYSTEM)).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.tenantRoleOf("acme", "root")).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.usersWithTenantRole("acme", TenantRole.TENANT_ADMIN)).thenReturn(List.of("root", "root2"));

        assertThatCode(() -> administration.assignTenantRole(SecurityContext.SYSTEM, "acme", "root", TenantRole.ADMIN))
                .doesNotThrowAnyException();
    }

    @Test
    void serviceAccount_holdsUserOnly() {
        assertThatThrownBy(() -> administration.assignTenantRole(ADMIN, "acme", "_daemon-01", TenantRole.CREATOR))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("service account");
    }

    @Test
    void unknownUser_isNotFound() {
        when(userService.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> administration.assignTenantRole(ADMIN, "acme", "ghost", TenantRole.CREATOR))
                .isInstanceOfSatisfying(
                        RoleAssignmentException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(RoleAssignmentException.Kind.NOT_FOUND));
    }

    @Test
    void externalTenantRoles_cannotBeChangedHere() {
        when(permissionService.storesTenantRoles()).thenReturn(false);

        assertThatThrownBy(() -> administration.assignTenantRole(ADMIN, "acme", "bob", TenantRole.CREATOR))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("external");
    }

    @Test
    void tenantRole_needsTenantWrite() {
        SecurityContext bob = SecurityContext.user("bob", "acme", List.of());
        doThrow(new PermissionDeniedException(bob, new Resource.Tenant("acme"), Action.WRITE))
                .when(permissionService)
                .enforce(bob, new Resource.Tenant("acme"), Action.WRITE);

        assertThatThrownBy(() -> administration.assignTenantRole(bob, "acme", "carol", TenantRole.CREATOR))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void projectRole_storedForAnExistingTeam() {
        when(projectService.findByTenantAndName("acme", "proj"))
                .thenReturn(Optional.of(
                        ProjectDocument.builder().tenantId("acme").name("proj").build()));
        when(teamService.existsByTenantAndName("acme", "eng")).thenReturn(true);

        administration.assignProjectRole(ADMIN, "acme", "proj", RoleSubjectType.TEAM, "eng", ProjectRole.MEMBER);

        verify(assignments).setProjectRole("acme", "proj", RoleSubjectType.TEAM, "eng", ProjectRole.MEMBER, "adm");
    }

    @Test
    void projectRole_refusedForSystemProjects() {
        when(projectService.findByTenantAndName("acme", "_tenant"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("_tenant")
                        .build()));

        assertThatThrownBy(() -> administration.assignProjectRole(
                        ADMIN, "acme", "_tenant", RoleSubjectType.USER, "bob", ProjectRole.MEMBER))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("system project");
    }

    @Test
    void projectRole_unknownSubject_isNotFound() {
        when(projectService.findByTenantAndName("acme", "proj"))
                .thenReturn(Optional.of(
                        ProjectDocument.builder().tenantId("acme").name("proj").build()));
        when(userService.existsByTenantAndName("acme", "ghost")).thenReturn(false);

        assertThatThrownBy(() -> administration.assignProjectRole(
                        ADMIN, "acme", "proj", RoleSubjectType.USER, "ghost", ProjectRole.MEMBER))
                .isInstanceOf(RoleAssignmentException.class)
                .hasMessageContaining("ghost");
    }
}
