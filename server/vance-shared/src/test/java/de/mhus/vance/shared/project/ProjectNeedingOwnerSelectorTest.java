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
            mock(ProjectRepository.class),
            mongoTemplate,
            mock(AuditService.class),
            mock(de.mhus.vance.shared.megadodo.MegadodoService.class),
            provider);

    @Test
    void findProjectsNeedingOwner_selectsOnlyTheStatusesThatWantToRun() {
        when(mongoTemplate.find(any(Query.class), eq(ProjectDocument.class))).thenReturn(List.of());

        service.findProjectsNeedingOwner(Duration.ofMinutes(5), 20);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(ProjectDocument.class));
        Document status = statusCriterion(captor.getValue());
        assertThat(status).isNotNull();
        assertThat(status.get("$in"))
                .isEqualTo(List.of(ProjectStatus.INIT, ProjectStatus.RECOVERING, ProjectStatus.RUNNING));
        // Explicitly: the two suspend states and the terminal one are out.
        assertThat((List<Object>) status.get("$in"))
                .doesNotContain(ProjectStatus.SUSPENDED, ProjectStatus.SUSPENDING, ProjectStatus.CLOSED);
    }

    @Test
    void findPendingPlacement_excludesHomelessRows() {
        // The demand report evaluates every pending row, and the placement
        // evaluation refuses a homeless one — a stale mark must not reach it.
        when(mongoTemplate.find(any(Query.class), eq(ProjectDocument.class))).thenReturn(List.of());

        service.findPendingPlacement(Duration.ofMinutes(5), Duration.ofHours(1));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(ProjectDocument.class));
        Document lifecycle = clause(captor.getValue(), "lifecycleType");
        assertThat(lifecycle).isNotNull();
        assertThat(lifecycle.get("$ne")).isEqualTo(LifecycleType.HOMELESS);
    }

    @Test
    void isHomeless_document_readsNameAndLifecycleType() {
        assertThat(ProjectService.isHomeless(ProjectDocument.builder()
                        .name("_tenant")
                        .lifecycleType(LifecycleType.HOMELESS)
                        .build()))
                .isTrue();
        assertThat(ProjectService.isHomeless(ProjectDocument.builder()
                        .name("_user_old")
                        .lifecycleType(LifecycleType.HOMELESS)
                        .build()))
                .as("a stale HOMELESS hub row counts as homeless everywhere")
                .isTrue();
        assertThat(ProjectService.isHomeless(ProjectDocument.builder()
                        .name("_user_new")
                        .lifecycleType(LifecycleType.AUTO)
                        .build()))
                .isFalse();
    }

    private static Document clause(Query query, String field) {
        Object and = query.getQueryObject().get("$and");
        if (and instanceof List<?> clauses) {
            for (Object c : clauses) {
                if (c instanceof Document doc && doc.get(field) instanceof Document value) {
                    return value;
                }
            }
        }
        return null;
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
