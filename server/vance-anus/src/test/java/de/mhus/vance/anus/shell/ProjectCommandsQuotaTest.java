package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.cluster.ProjectClusterService;
import de.mhus.vance.anus.maintenance.ProjectMaintenanceService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The shell refuses at the active-project quota like the product surfaces,
 * and steps past it only on {@code --ignore-quota} — so an overshoot is
 * always something the operator chose.
 */
class ProjectCommandsQuotaTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectQuotaService quotaService = mock(ProjectQuotaService.class);

    @SuppressWarnings("unchecked")
    private final ProjectCommands commands = new ProjectCommands(
            projectService,
            new ProjectClusterService(mock(AnusBrainClient.class)),
            mock(ProjectMaintenanceService.class),
            mock(ProjectGroupService.class),
            quotaService,
            mock(ObjectProvider.class));

    @BeforeEach
    void setUp() {
        ProjectDocument created = project("research", ProjectStatus.INIT);
        when(projectService.create(anyString(), anyString(), any(), any(), any()))
                .thenReturn(created);
        when(projectService.reopen(anyString(), anyString(), any())).thenReturn(created);
        when(quotaService.status("acme")).thenReturn(new ProjectQuotaService.Status(6, 5));
    }

    private static ProjectDocument project(String name, ProjectStatus status) {
        ProjectDocument p = new ProjectDocument();
        p.setTenantId("acme");
        p.setName(name);
        p.setStatus(status);
        return p;
    }

    private void atLimit() {
        doThrow(new ProjectQuotaService.ProjectQuotaExceededException(5, 5))
                .when(quotaService)
                .checkOneMore("acme");
    }

    @Test
    void create_refusesAtTheLimit() {
        atLimit();

        String out = commands.create("acme", "research", null, null, null, false);

        assertThat(out).startsWith("Refused:").contains("5/5").contains("--ignore-quota");
        verify(projectService, never()).create(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void create_ignoreQuotaGoesThroughAndShowsTheOvershoot() {
        atLimit();

        String out = commands.create("acme", "research", null, null, null, true);

        assertThat(out).startsWith("Created:").contains("6/5 active (over the limit)");
        verify(quotaService, never()).checkOneMore("acme");
    }

    @Test
    void create_unlimitedTenantShowsNoQuotaLine() {
        when(quotaService.status("acme")).thenReturn(new ProjectQuotaService.Status(3, 0));

        String out = commands.create("acme", "research", null, null, null, false);

        assertThat(out).startsWith("Created:").doesNotContain("quota");
    }

    @Test
    void reopen_refusesAtTheLimit() {
        when(projectService.findByTenantAndName("acme", "research"))
                .thenReturn(Optional.of(project("research", ProjectStatus.CLOSED)));
        atLimit();

        String out = commands.reopen("acme", "research", false);

        assertThat(out).startsWith("Refused:");
        verify(projectService, never()).reopen(anyString(), anyString(), any());
    }

    @Test
    void reopen_ignoreQuotaGoesThrough() {
        when(projectService.findByTenantAndName("acme", "research"))
                .thenReturn(Optional.of(project("research", ProjectStatus.CLOSED)));
        atLimit();

        String out = commands.reopen("acme", "research", true);

        assertThat(out).startsWith("Reopened:");
    }

    @Test
    void reopen_ofAnOpenProjectIsAStatusAnswerNotAQuotaOne() {
        when(projectService.findByTenantAndName("acme", "research"))
                .thenReturn(Optional.of(project("research", ProjectStatus.RUNNING)));
        atLimit();

        String out = commands.reopen("acme", "research", false);

        assertThat(out).contains("only a closed project");
        verify(projectService, never()).reopen(anyString(), anyString(), any());
    }
}
