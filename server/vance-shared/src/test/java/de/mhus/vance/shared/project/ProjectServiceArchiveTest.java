package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.PermissionService;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Archive and its way back: {@code close} remembers the group the project
 * came from, {@code reopen} restores the status and the group the caller
 * chose, and {@code countActive} is what the active-project quota counts.
 */
class ProjectServiceArchiveTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> permissions = mock(ObjectProvider.class);

    private final ProjectService service = new ProjectService(
            repository, mongoTemplate, mock(AuditService.class), mock(MegadodoService.class), permissions);

    private ProjectDocument project(ProjectStatus status, String group) {
        return ProjectDocument.builder()
                .tenantId("acme")
                .name("kunde-x")
                .status(status)
                .projectGroupId(group)
                .build();
    }

    private Update capturedUpdate() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate)
                .findAndModify(
                        any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(ProjectDocument.class));
        return update.getValue();
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate)
                .findAndModify(
                        query.capture(), any(Update.class), any(FindAndModifyOptions.class), eq(ProjectDocument.class));
        return query.getValue();
    }

    @Test
    void close_remembersTheGroupItCameFrom() {
        when(repository.findByTenantIdAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(project(ProjectStatus.RUNNING, "sales")));
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(project(ProjectStatus.CLOSED, "archived"));

        service.close("acme", "kunde-x", "archived");

        Document set = (Document) capturedUpdate().getUpdateObject().get("$set");
        assertThat(set.get("projectGroupId")).isEqualTo("archived");
        assertThat(set.get("archivedFromGroupId")).isEqualTo("sales");
    }

    @Test
    void close_again_keepsTheOriginalOrigin() {
        // A second close of an already archived project must not record the
        // archived group as where the project came from.
        when(repository.findByTenantIdAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(project(ProjectStatus.CLOSED, "archived")));
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(project(ProjectStatus.CLOSED, "archived"));

        service.close("acme", "kunde-x", "archived");

        Document set = (Document) capturedUpdate().getUpdateObject().get("$set");
        assertThat(set).doesNotContainKey("archivedFromGroupId");
    }

    @Test
    void reopen_setsInitAndTheGivenGroup_andClearsTheOrigin() {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(project(ProjectStatus.INIT, "sales"));

        ProjectDocument reopened = service.reopen("acme", "kunde-x", "sales");

        assertThat(reopened.getStatus()).isEqualTo(ProjectStatus.INIT);
        Document update = capturedUpdate().getUpdateObject();
        Document set = (Document) update.get("$set");
        assertThat(set.get("status")).isEqualTo(ProjectStatus.INIT);
        assertThat(set.get("projectGroupId")).isEqualTo("sales");
        assertThat((Document) update.get("$unset")).containsKey("archivedFromGroupId");
    }

    @Test
    void reopen_matchesOnlyClosedProjects() {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(project(ProjectStatus.INIT, "sales"));

        service.reopen("acme", "kunde-x", "sales");

        assertThat(capturedQuery().getQueryObject().get("status")).isEqualTo(ProjectStatus.CLOSED);
    }

    @Test
    void reopen_ofAProjectThatIsNotArchived_isAConflict() {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(null);
        when(repository.findByTenantIdAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(project(ProjectStatus.RUNNING, "sales")));

        assertThatThrownBy(() -> service.reopen("acme", "kunde-x", "sales"))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void reopen_ofAnUnknownProject_isNotFound() {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(null);
        when(repository.findByTenantIdAndName("acme", "kunde-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reopen("acme", "kunde-x", null))
                .isInstanceOf(ProjectService.ProjectNotFoundException.class);
    }

    @Test
    void countActive_excludesSystemAndClosed() {
        when(mongoTemplate.count(any(Query.class), eq(ProjectDocument.class))).thenReturn(4L);

        assertThat(service.countActive("acme")).isEqualTo(4L);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(query.capture(), eq(ProjectDocument.class));
        Document q = query.getValue().getQueryObject();
        assertThat(q.get("tenantId")).isEqualTo("acme");
        assertThat(q.get("kind")).isEqualTo(new Document("$ne", ProjectKind.SYSTEM));
        assertThat(q.get("status")).isEqualTo(new Document("$ne", ProjectStatus.CLOSED));
    }
}
