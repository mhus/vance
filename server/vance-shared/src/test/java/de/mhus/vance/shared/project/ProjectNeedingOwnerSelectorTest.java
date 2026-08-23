package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.permission.PermissionService;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The candidate selector behind the Boot-Self-Pull and the Cluster-Master
 * Distributor. It reads {@code status} as <em>intent</em>, and a suspended
 * project's intent is "do not run" — recovery must not overrule it, or an
 * operator's suspend silently expires with the holder's lease on the next
 * restart.
 */
class ProjectNeedingOwnerSelectorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionService> provider = mock(ObjectProvider.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ProjectService service = new ProjectService(
            mock(ProjectRepository.class), mongoTemplate, mock(AuditService.class), provider);

    @Test
    void findProjectsNeedingOwner_selectsOnlyTheStatusesThatWantToRun() {
        when(mongoTemplate.find(any(Query.class), eq(ProjectDocument.class)))
                .thenReturn(List.of());

        service.findProjectsNeedingOwner(Duration.ofMinutes(5), 20);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(ProjectDocument.class));
        Document status = statusCriterion(captor.getValue());
        assertThat(status).isNotNull();
        assertThat(status.get("$in")).isEqualTo(List.of(
                ProjectStatus.INIT, ProjectStatus.RECOVERING, ProjectStatus.RUNNING));
        // Explicitly: the two suspend states and the terminal one are out.
        assertThat((List<Object>) status.get("$in"))
                .doesNotContain(ProjectStatus.SUSPENDED, ProjectStatus.SUSPENDING,
                        ProjectStatus.CLOSED);
    }

    /** Digs the {@code status} clause out of the {@code $and} the selector builds. */
    private static Document statusCriterion(Query query) {
        Document root = query.getQueryObject();
        Object and = root.get("$and");
        if (!(and instanceof List<?> clauses)) {
            return (Document) root.get("status");
        }
        for (Object clause : clauses) {
            if (clause instanceof Document doc && doc.get("status") instanceof Document status) {
                return status;
            }
        }
        return null;
    }
}
