package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * {@link ThinkProcessService#countBySession} — the status→bucket folding
 * behind the clients' process badge. Mongo is mocked; what matters is the
 * mapping, the exclusion and that terminal processes drop out.
 */
class ThinkProcessServiceCountsTest {

    private static final String TENANT = "t";
    private static final String SESSION = "s-1";

    private ThinkProcessRepository repository;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        service = new ThinkProcessService(
                repository,
                mock(MongoTemplate.class),
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

    private void givenProcesses(ThinkProcessDocument... docs) {
        when(repository.findByTenantIdAndSessionId(TENANT, SESSION))
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
