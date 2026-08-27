package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.PermissionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The invariants of the project entity itself, which is what {@code rename} and
 * {@code delete} may not be talked out of — the lease guard and the typed
 * confirmation live a layer up, in {@code ProjectMaintenanceService} and the
 * shell, and can be forced. These cannot.
 */
class ProjectServiceRenameDeleteTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MegadodoService megadodoService = mock(MegadodoService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> permissions = mock(ObjectProvider.class);

    private final ProjectService service = new ProjectService(
            repository, mongoTemplate, auditService, megadodoService, permissions);

    @Test
    void rename_refuses_systemProjects() {
        when(repository.findByTenantIdAndName("acme", "_user_marvin"))
                .thenReturn(Optional.of(project("_user_marvin", ProjectKind.SYSTEM)));

        assertThatThrownBy(() -> service.rename("acme", "_user_marvin", "marvin"))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);
    }

    @Test
    void rename_refuses_theReservedPrefix() {
        when(repository.findByTenantIdAndName("acme", "p1"))
                .thenReturn(Optional.of(project("p1", ProjectKind.NORMAL)));

        assertThatThrownBy(() -> service.rename("acme", "p1", "_sneaky"))
                .isInstanceOf(ProjectService.ReservedProjectNameException.class);
    }

    @Test
    void rename_refuses_aTakenName() {
        when(repository.findByTenantIdAndName("acme", "p1"))
                .thenReturn(Optional.of(project("p1", ProjectKind.NORMAL)));
        when(repository.existsByTenantIdAndName("acme", "p2")).thenReturn(true);

        assertThatThrownBy(() -> service.rename("acme", "p1", "p2"))
                .isInstanceOf(ProjectService.ProjectAlreadyExistsException.class);
    }

    @Test
    void rename_refuses_aNameThatWouldEscapeTheWorkspaceRoot() {
        when(repository.findByTenantIdAndName("acme", "p1"))
                .thenReturn(Optional.of(project("p1", ProjectKind.NORMAL)));

        // The name becomes a path segment: <root>/<tenant>/<project>.
        assertThatThrownBy(() -> service.rename("acme", "p1", "../../etc"))
                .isInstanceOf(ProjectService.ReservedProjectNameException.class)
                .hasMessageContaining("path segment");
    }

    @Test
    void rename_refuses_aNameWithASlash() {
        when(repository.findByTenantIdAndName("acme", "p1"))
                .thenReturn(Optional.of(project("p1", ProjectKind.NORMAL)));

        assertThatThrownBy(() -> service.rename("acme", "p1", "team/p2"))
                .isInstanceOf(ProjectService.ReservedProjectNameException.class);
    }

    @Test
    void delete_refuses_systemProjects() {
        when(repository.findByTenantIdAndName("acme", "_vance"))
                .thenReturn(Optional.of(project("_vance", ProjectKind.SYSTEM)));

        assertThatThrownBy(() -> service.delete("acme", "_vance"))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delete_isIdempotent_onAProjectThatIsAlreadyGone() {
        when(repository.findByTenantIdAndName("acme", "gone")).thenReturn(Optional.empty());

        // Re-running an interrupted delete must not fail, or the recovery path
        // that the maintenance service relies on would not exist.
        assertThat(service.delete("acme", "gone")).isFalse();
        verify(auditService, never()).projectDelete("acme", "gone");
    }

    @Test
    void delete_recordsAudit_andActivityFeed() {
        ProjectDocument p1 = project("p1", ProjectKind.NORMAL);
        when(repository.findByTenantIdAndName("acme", "p1")).thenReturn(Optional.of(p1));

        assertThat(service.delete("acme", "p1")).isTrue();

        verify(repository).delete(p1);
        verify(auditService).projectDelete("acme", "p1");
        verify(megadodoService).projectDeleted("acme", "p1", null);
    }

    private static ProjectDocument project(String name, ProjectKind kind) {
        return ProjectDocument.builder().tenantId("acme").name(name).kind(kind).build();
    }
}
