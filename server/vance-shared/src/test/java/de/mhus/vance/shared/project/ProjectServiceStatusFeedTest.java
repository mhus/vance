package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.PermissionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Stop and resume transitions of the desired state reach the Megadodo feed;
 * the first start and lost races do not.
 */
class ProjectServiceStatusFeedTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MegadodoService megadodo = mock(MegadodoService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> permissions = mock(ObjectProvider.class);

    private final ProjectService service =
            new ProjectService(repository, mongoTemplate, mock(AuditService.class), megadodo, permissions);

    private void transitionWins(ProjectStatus target) {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("kunde-x")
                        .status(target)
                        .build());
    }

    @Test
    void suspend_isRecorded() {
        transitionWins(ProjectStatus.SUSPENDED);
        service.transitionStatus("acme", "kunde-x", ProjectStatus.RUNNING, ProjectStatus.SUSPENDED);
        verify(megadodo).projectSuspended("acme", "kunde-x", null);
    }

    @Test
    void idle_staysSilent() {
        transitionWins(ProjectStatus.IDLE);
        service.transitionStatus("acme", "kunde-x", ProjectStatus.RUNNING, ProjectStatus.IDLE);
        verifyNoInteractions(megadodo);
    }

    @Test
    void resumeFromSuspended_isRecorded() {
        transitionWins(ProjectStatus.RUNNING);
        service.transitionStatus("acme", "kunde-x", ProjectStatus.SUSPENDED, ProjectStatus.RUNNING);
        verify(megadodo).projectResumed("acme", "kunde-x", "SUSPENDED", null);
    }

    @Test
    void firstStart_staysSilent() {
        transitionWins(ProjectStatus.RUNNING);
        service.transitionStatus("acme", "kunde-x", ProjectStatus.INIT, ProjectStatus.RUNNING);
        verifyNoInteractions(megadodo);
    }

    @Test
    void lostRace_isNotRecorded() {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(null);
        when(repository.findByTenantIdAndName("acme", "kunde-x"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("kunde-x")
                        .status(ProjectStatus.CLOSED)
                        .build()));
        assertThatThrownBy(() ->
                        service.transitionStatus("acme", "kunde-x", ProjectStatus.RUNNING, ProjectStatus.SUSPENDED))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
        verify(megadodo, never()).projectSuspended(anyString(), anyString(), any());
    }
}
