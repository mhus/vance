package de.mhus.vance.anus.maintenance;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that make a project-wide delete safe to run twice and unsafe to run
 * blindly. Everything here is about the orchestration, not about any one
 * entity — the handlers are fakes.
 */
class ProjectMaintenanceServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @Test
    void delete_runsHandlersInOrderAscending() {
        List<String> calls = new ArrayList<>();
        FakeHandler cascade = new FakeHandler("chat", 100, 3).recording(calls);
        FakeHandler plain = new FakeHandler("docs", 500, 7).recording(calls);
        ProjectMaintenanceService service = service(List.of(plain, cascade), running("p1"));

        service.delete("acme", "p1", false);

        assertThat(calls).containsExactly("chat", "docs");
    }

    @Test
    void delete_removesProjectDocument_whenEveryHandlerSucceeded() {
        ProjectMaintenanceService service =
                service(List.of(new FakeHandler("docs", 500, 4)), running("p1"));

        MaintenanceReport report = service.delete("acme", "p1", false);

        verify(projectService).delete("acme", "p1");
        assertThat(report.total()).isEqualTo(5); // 4 rows + the project document
    }

    @Test
    void delete_keepsProjectDocument_whenAnEntityFailed() {
        FakeHandler broken = new FakeHandler("docs", 500, 0);
        broken.failure = new IllegalStateException("mongo down");
        ProjectMaintenanceService service = service(List.of(broken), running("p1"));

        MaintenanceReport report = service.delete("acme", "p1", false);

        // The document is the only way left to address what was not deleted.
        verify(projectService, never()).delete("acme", "p1");
        assertThat(report.entities())
                .anySatisfy(e -> assertThat(e.note()).contains("mongo down"))
                .anySatisfy(e -> assertThat(e.handlerId()).isEqualTo("project"));
    }

    @Test
    void delete_continuesWithRemainingEntities_afterOneFailed() {
        FakeHandler broken = new FakeHandler("docs", 100, 0);
        broken.failure = new IllegalStateException("boom");
        FakeHandler later = new FakeHandler("sessions", 500, 2);
        ProjectMaintenanceService service = service(List.of(broken, later), running("p1"));

        service.delete("acme", "p1", false);

        assertThat(later.deleted).isTrue();
    }

    @Test
    void delete_refuses_whileAPodHoldsALiveLease() {
        ProjectDocument held = running("p1");
        held.setHomePodId("pod-7");
        held.setHomeNode("node-a");
        held.setClaimedAt(Instant.now());
        ProjectMaintenanceService service =
                service(List.of(new FakeHandler("docs", 500, 1)), held);

        assertThatThrownBy(() -> service.delete("acme", "p1", false))
                .isInstanceOf(ProjectMaintenanceService.ProjectInUseException.class)
                .hasMessageContaining("pod-7");
    }

    @Test
    void delete_proceedsOnForce_whenTheHolderIsKnownToBeGone() {
        ProjectDocument held = running("p1");
        held.setHomePodId("pod-7");
        held.setClaimedAt(Instant.now());
        ProjectMaintenanceService service =
                service(List.of(new FakeHandler("docs", 500, 1)), held);

        service.delete("acme", "p1", true);

        verify(projectService).delete("acme", "p1");
    }

    @Test
    void delete_refuses_systemProjects_evenWithForce() {
        ProjectDocument hub = running("_user_marvin");
        hub.setKind(ProjectKind.SYSTEM);
        ProjectMaintenanceService service = service(List.of(), hub);

        assertThatThrownBy(() -> service.delete("acme", "_user_marvin", true))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);
    }

    @Test
    void delete_attachesTheHandlersNote_soADeliberateLeftoverIsVisible() {
        FakeHandler refs = new FakeHandler("inbox-refs", 800, 0);
        refs.deleteNote = "4 threads keep their reference";
        ProjectMaintenanceService service = service(List.of(refs), running("p1"));

        MaintenanceReport report = service.delete("acme", "p1", false);

        assertThat(report.entities())
                .anySatisfy(e -> assertThat(e.note()).isEqualTo("4 threads keep their reference"));
    }

    @Test
    void rename_writesNothing_whenAnyHandlerBlocks() {
        FakeHandler blocking = new FakeHandler("workspace", 500, 0);
        blocking.blocker = "folder already exists";
        FakeHandler willing = new FakeHandler("docs", 500, 3);
        ProjectMaintenanceService service =
                service(List.of(blocking, willing), running("p1"));
        when(projectService.existsByTenantAndName("acme", "p2")).thenReturn(false);

        assertThatThrownBy(() -> service.rename("acme", "p1", "p2", false))
                .isInstanceOf(ProjectMaintenanceService.RenameBlockedException.class)
                .hasMessageContaining("folder already exists");
        assertThat(willing.renamed).isFalse();
        verify(projectService, never()).rename("acme", "p1", "p2");
    }

    @Test
    void rename_renamesTheProjectDocumentLast() {
        FakeHandler docs = new FakeHandler("docs", 500, 3);
        ProjectMaintenanceService service = service(List.of(docs), running("p1"));
        when(projectService.existsByTenantAndName("acme", "p2")).thenReturn(false);

        service.rename("acme", "p1", "p2", false);

        assertThat(docs.renamed).isTrue();
        verify(projectService).rename("acme", "p1", "p2");
    }

    @Test
    void rename_refuses_whenTheTargetNameIsTaken() {
        ProjectMaintenanceService service = service(List.of(), running("p1"));
        when(projectService.existsByTenantAndName("acme", "p2")).thenReturn(true);

        assertThatThrownBy(() -> service.rename("acme", "p1", "p2", false))
                .isInstanceOf(ProjectService.ProjectAlreadyExistsException.class);
    }

    @Test
    void inspect_writesNothing() {
        FakeHandler docs = new FakeHandler("docs", 500, 9);
        ProjectMaintenanceService service = service(List.of(docs), running("p1"));

        MaintenanceReport report = service.inspect("acme", "p1");

        assertThat(report.total()).isEqualTo(9);
        assertThat(docs.deleted).isFalse();
        assertThat(docs.renamed).isFalse();
        verify(projectService, never()).delete("acme", "p1");
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────

    private ProjectMaintenanceService service(
            List<ProjectDataHandler> handlers, ProjectDocument project) {
        when(projectService.findByTenantAndName("acme", project.getName()))
                .thenReturn(Optional.of(project));
        // No collections in the fake database — the coverage probe has nothing
        // to find, which keeps these tests about the orchestration.
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of());
        when(mongoTemplate.getCollectionName(ProjectDocument.class)).thenReturn("projects");
        return new ProjectMaintenanceService(
                handlers, projectService, mongoTemplate, Duration.ofMinutes(5));
    }

    private static ProjectDocument running(String name) {
        return ProjectDocument.builder().tenantId("acme").name(name)
                .kind(ProjectKind.NORMAL).build();
    }

    /** A handler that records what it was asked and can be told to misbehave. */
    private static final class FakeHandler implements ProjectDataHandler {
        private final String id;
        private final int order;
        private final long rows;
        private @Nullable List<String> calls;
        private @Nullable RuntimeException failure;
        private @Nullable String blocker;
        private @Nullable String deleteNote;
        private boolean deleted;
        private boolean renamed;

        FakeHandler(String id, int order, long rows) {
            this.id = id;
            this.order = order;
            this.rows = rows;
        }

        FakeHandler recording(List<String> calls) {
            this.calls = calls;
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> collections() {
            return Set.of(id);
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public long count(String tenantId, String projectId) {
            return rows;
        }

        @Override
        public long delete(String tenantId, String projectId) {
            if (calls != null) {
                calls.add(id);
            }
            if (failure != null) {
                throw failure;
            }
            deleted = true;
            return rows;
        }

        @Override
        public long rename(String tenantId, String projectId, String newProjectId) {
            renamed = true;
            return rows;
        }

        @Override
        public @Nullable String deleteNote(String tenantId, String projectId) {
            return deleteNote;
        }

        @Override
        public @Nullable String renameBlocker(
                String tenantId, String projectId, String newProjectId) {
            return blocker;
        }
    }
}
