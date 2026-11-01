package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Taking a lease from a holder that is gone is a compare-and-set on <em>exactly the lease that was
 * observed</em>: a holder that renewed in the meantime was alive after all, and the write must miss.
 */
class ProjectServiceTakeoverTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MegadodoService megadodo = mock(MegadodoService.class);

    @SuppressWarnings("unchecked")
    private final ProjectService service = new ProjectService(
            mock(ProjectRepository.class), mongo, mock(AuditService.class), megadodo, mock(ObjectProvider.class));

    private static ProjectDocument observed(String holder, Instant claimedAt) {
        return ProjectDocument.builder()
                .tenantId("acme")
                .name("work")
                .homePodId(holder)
                .homeNode("old-node")
                .claimedAt(claimedAt)
                .status(ProjectStatus.RUNNING)
                .build();
    }

    @Test
    void theWriteIsGuardedByTheObservedHolderAndRenewal() {
        Instant claimed = Instant.parse("2026-11-01T12:00:00Z");
        ProjectDocument taken = observed("pod-new", Instant.now());
        when(mongo.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(taken);

        Optional<ProjectDocument> result =
                service.claimFromGoneHolder(observed("pod-dead", claimed), "pod-new", "new-node", "10.0.0.9:9990");

        assertThat(result).containsSame(taken);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo)
                .findAndModify(
                        query.capture(), any(Update.class), any(FindAndModifyOptions.class), eq(ProjectDocument.class));
        String criteria = query.getValue().getQueryObject().toString();
        assertThat(criteria)
                .contains("homePodId=pod-dead")
                .contains("claimedAt=")
                .contains("status=");
        verify(megadodo)
                .projectHomeClaimed(
                        eq("acme"), eq("work"), eq("new-node"), eq("pod-new"), any(), eq("old-node"), eq(claimed));
    }

    @Test
    void aHolderThatRenewedInTheMeantime_missesTheWrite() {
        when(mongo.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class)))
                .thenReturn(null);

        assertThat(service.claimFromGoneHolder(observed("pod-dead", Instant.now()), "pod-new", "n", "a"))
                .isEmpty();

        verify(megadodo, never()).projectHomeClaimed(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nothingToTakeFromTheCallerItself_orFromNobody() {
        assertThat(service.claimFromGoneHolder(observed("pod-new", Instant.now()), "pod-new", "n", "a"))
                .isEmpty();
        assertThat(service.claimFromGoneHolder(observed(null, null), "pod-new", "n", "a"))
                .isEmpty();

        verify(mongo, never())
                .findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(ProjectDocument.class));
    }
}
