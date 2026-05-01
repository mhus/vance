package de.mhus.vance.shared.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HomeBootstrapServiceTest {

    private ProjectGroupService projectGroupService;
    private ProjectService projectService;
    private UserService userService;
    private HomeBootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        projectGroupService = mock(ProjectGroupService.class);
        projectService = mock(ProjectService.class);
        userService = mock(UserService.class);
        bootstrap = new HomeBootstrapService(projectGroupService, projectService, userService);
    }

    @Test
    void hubProjectName_buildsDeterministicName() {
        assertThat(HomeBootstrapService.hubProjectName("alice"))
                .isEqualTo("_user_alice");
    }

    @Test
    void ensureHome_isIdempotent_whenProjectAlreadyExists() {
        ProjectGroupDocument existingGroup = group("_home");
        ProjectDocument existing = project("_user_alice");
        when(projectGroupService.findByTenantAndName("acme", "_home"))
                .thenReturn(Optional.of(existingGroup));
        when(projectService.findByTenantAndName("acme", "_user_alice"))
                .thenReturn(Optional.of(existing));

        ProjectDocument result = bootstrap.ensureHome("acme", "alice");

        assertThat(result).isSameAs(existing);
        // No creates on repeat calls.
        verify(projectGroupService, never())
                .create(any(), any(), any());
        verify(projectService, never())
                .create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureHome_createsGroupAndProject_onFirstCall() {
        ProjectGroupDocument freshGroup = group("_home");
        ProjectDocument freshProject = project("_user_alice");
        when(projectGroupService.findByTenantAndName("acme", "_home"))
                .thenReturn(Optional.empty());
        when(projectGroupService.create("acme", "_home", "Home"))
                .thenReturn(freshGroup);
        when(projectService.findByTenantAndName("acme", "_user_alice"))
                .thenReturn(Optional.empty());
        when(projectService.create(eq("acme"), eq("_user_alice"),
                any(), eq("_home"), eq(null), eq(ProjectKind.SYSTEM)))
                .thenReturn(freshProject);

        ProjectDocument result = bootstrap.ensureHome("acme", "alice");

        assertThat(result).isSameAs(freshProject);
        verify(projectGroupService).create("acme", "_home", "Home");
        verify(projectService).create(eq("acme"), eq("_user_alice"),
                any(), eq("_home"), eq(null), eq(ProjectKind.SYSTEM));
    }

    @Test
    void ensureVance_createsTenantWideProject_underHomeGroup() {
        when(projectGroupService.findByTenantAndName("acme", "_home"))
                .thenReturn(Optional.of(group("_home")));
        when(projectService.findByTenantAndName("acme", "_vance"))
                .thenReturn(Optional.empty());
        when(projectService.create(eq("acme"), eq("_vance"),
                eq("Vance"), eq("_home"), eq(null), eq(ProjectKind.SYSTEM)))
                .thenReturn(project("_vance"));

        ProjectDocument result = bootstrap.ensureVance("acme");

        assertThat(result.getName()).isEqualTo("_vance");
    }

    @Test
    void resolveOrAutoProvision_returnsExisting_withoutTouchingUserService() {
        when(projectService.findByTenantAndName("acme", "regular-proj"))
                .thenReturn(Optional.of(project("regular-proj")));

        Optional<ProjectDocument> result =
                bootstrap.resolveOrAutoProvision("acme", "regular-proj");

        assertThat(result).isPresent();
        verify(userService, never()).existsByTenantAndName(any(), any());
    }

    @Test
    void resolveOrAutoProvision_skips_whenNameDoesNotMatchHubPattern() {
        when(projectService.findByTenantAndName("acme", "random-proj"))
                .thenReturn(Optional.empty());

        Optional<ProjectDocument> result =
                bootstrap.resolveOrAutoProvision("acme", "random-proj");

        assertThat(result).isEmpty();
        verify(userService, never()).existsByTenantAndName(any(), any());
    }

    @Test
    void resolveOrAutoProvision_skips_whenUserDoesNotExist() {
        when(projectService.findByTenantAndName("acme", "_user_ghost"))
                .thenReturn(Optional.empty());
        when(userService.existsByTenantAndName("acme", "ghost"))
                .thenReturn(false);

        Optional<ProjectDocument> result =
                bootstrap.resolveOrAutoProvision("acme", "_user_ghost");

        assertThat(result).isEmpty();
        // User check happened, but no create.
        verify(userService, times(1)).existsByTenantAndName("acme", "ghost");
        verify(projectService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveOrAutoProvision_skips_whenHubSuffixIsEmpty() {
        // Bare "_user_" (no login after) is not a valid Hub project.
        when(projectService.findByTenantAndName("acme", "_user_"))
                .thenReturn(Optional.empty());

        Optional<ProjectDocument> result =
                bootstrap.resolveOrAutoProvision("acme", "_user_");

        assertThat(result).isEmpty();
        verify(userService, never()).existsByTenantAndName(any(), any());
    }

    @Test
    void resolveOrAutoProvision_provisions_whenHubMatches_andUserExists() {
        when(projectService.findByTenantAndName("acme", "_user_alice"))
                .thenReturn(Optional.empty(), Optional.empty(), // first lookups
                        Optional.empty()); // ensureHome's own lookup
        when(userService.existsByTenantAndName("acme", "alice")).thenReturn(true);
        when(projectGroupService.findByTenantAndName("acme", "_home"))
                .thenReturn(Optional.of(group("_home")));
        when(projectService.create(eq("acme"), eq("_user_alice"),
                any(), eq("_home"), eq(null), eq(ProjectKind.SYSTEM)))
                .thenReturn(project("_user_alice"));

        Optional<ProjectDocument> result =
                bootstrap.resolveOrAutoProvision("acme", "_user_alice");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("_user_alice");
    }

    private static ProjectGroupDocument group(String name) {
        ProjectGroupDocument g = new ProjectGroupDocument();
        g.setName(name);
        return g;
    }

    private static ProjectDocument project(String name) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        return p;
    }
}
