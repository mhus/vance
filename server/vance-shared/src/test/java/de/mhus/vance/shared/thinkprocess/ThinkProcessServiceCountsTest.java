package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link ThinkProcessService#countBySession} — the status→bucket folding
 * behind the clients' process badge. Mongo is mocked; what matters is the
 * mapping, the exclusion and that terminal processes drop out.
 */
class ThinkProcessServiceCountsTest {

    private static final String TENANT = "t";
    private static final String SESSION = "s-1";

    private ThinkProcessRepository repository;
    private MongoTemplate mongoTemplate;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new ThinkProcessService(
                repository,
                mongoTemplate,
                mock(ApplicationEventPublisher.class),
                mock(EngineMessageService.class));
    }

    @Test
    void countBySession_mapsEachStatusToItsBucket() {
        givenProcesses(
                doc("a", ThinkProcessStatus.RUNNING),
                doc("b", ThinkProcessStatus.RUNNING),
                doc("c", ThinkProcessStatus.BLOCKED),
                doc("d", ThinkProcessStatus.IDLE),
                doc("e", ThinkProcessStatus.INIT),
                doc("f", ThinkProcessStatus.PAUSED),
                doc("g", ThinkProcessStatus.SUSPENDED));

        ThinkProcessService.ProcessCounts counts =
                service.countBySession(TENANT, SESSION, null);

        assertThat(counts.running()).isEqualTo(2);
        assertThat(counts.blocked()).isEqualTo(1);
        assertThat(counts.waiting()).isEqualTo(4);
        assertThat(counts.total()).isEqualTo(7);
    }

    @Test
    void countBySession_ignoresClosedProcesses() {
        givenProcesses(
                doc("worker", ThinkProcessStatus.IDLE),
                doc("done", ThinkProcessStatus.CLOSED));

        ThinkProcessService.ProcessCounts counts =
                service.countBySession(TENANT, SESSION, null);

        assertThat(counts.waiting()).isEqualTo(1);
        assertThat(counts.total()).isEqualTo(1);
    }

    @Test
    void countBySession_excludesNamedProcess() {
        givenProcesses(
                doc("chat", ThinkProcessStatus.IDLE),
                doc("worker", ThinkProcessStatus.RUNNING));

        ThinkProcessService.ProcessCounts counts =
                service.countBySession(TENANT, SESSION, "chat");

        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.waiting()).isZero();
        assertThat(counts.total()).isEqualTo(1);
    }

    @Test
    void countBySession_toleratesMissingStatus() {
        givenProcesses(doc("legacy", null), doc("worker", ThinkProcessStatus.RUNNING));

        ThinkProcessService.ProcessCounts counts =
                service.countBySession(TENANT, SESSION, null);

        assertThat(counts.total()).isEqualTo(1);
    }

    @Test
    void processCounts_equalityIgnoresNothing_soCoalescingCanCompare() {
        assertThat(new ThinkProcessService.ProcessCounts(1, 2, 3))
                .isEqualTo(new ThinkProcessService.ProcessCounts(1, 2, 3));
        assertThat(new ThinkProcessService.ProcessCounts(1, 2, 3))
                .isNotEqualTo(new ThinkProcessService.ProcessCounts(1, 3, 2));
    }

    @Test
    void countBySession_readsOnlyStatusAndName() {
        // This runs on every status transition, and RUNNING↔IDLE flips once
        // per turn per process. Materialising whole documents meant a session
        // with forty workers pulled forty embedded pending-queues, param maps
        // and skill lists from Mongo to produce three integers.
        givenProcesses(doc("worker", ThinkProcessStatus.RUNNING));

        service.countBySession(TENANT, SESSION, null);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ThinkProcessDocument.class));
        // An inclusion projection naming exactly the two fields the fold
        // reads — anything else comes back with the row.
        assertThat(query.getValue().getFieldsObject().keySet()).contains("status", "name");
        // Never the whole row.
        verify(repository, never()).findByTenantIdAndSessionId(any(), any());
    }

    private void givenProcesses(ThinkProcessDocument... docs) {
        when(mongoTemplate.find(any(Query.class), eq(ThinkProcessDocument.class)))
                .thenReturn(List.of(docs));
    }

    private static ThinkProcessDocument doc(String name, ThinkProcessStatus status) {
        return ThinkProcessDocument.builder()
                .id("id-" + name)
                .tenantId(TENANT)
                .sessionId(SESSION)
                .name(name)
                .status(status)
                .build();
    }
}
