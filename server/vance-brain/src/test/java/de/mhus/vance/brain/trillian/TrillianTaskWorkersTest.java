package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TrillianInternalApi#listTaskWorkers} — the shared definition of
 * "which processes are a loop's task workers". Both {@code //trillian info}
 * and the insights state view render from it, so the filtering contract
 * (the loop itself never counts, closed ones never count) lives here once
 * instead of at every consumer.
 */
class TrillianTaskWorkersTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-worker";

    private ThinkProcessService thinkProcessService;
    private TrillianInternalApi api;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        api = new TrillianInternalApi(
                thinkProcessService,
                mock(EngineMessageRouter.class),
                mock(EngineMessageService.class),
                mock(ProcessEventEmitter.class),
                mock(ChatMessageService.class),
                mock(LaneScheduler.class),
                new de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry(java.util.List.of(
                        new de.mhus.vance.brain.trillian.nature.TrillianNatureVoid(thinkProcessService))));
    }

    @Test
    void listsLiveChildrenWithTheirTargetProject() {
        ThinkProcessDocument peer = process("peer-1", "trillian-user-loop", ThinkProcessStatus.IDLE);
        ThinkProcessDocument worker = process("w-1", "count-md", ThinkProcessStatus.RUNNING);
        worker.setProjectId("target-project");
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(peer, worker));

        List<TrillianInternalApi.TaskWorkerSnapshot> workers = api.listTaskWorkers(peer);

        // The target project is what makes a cross-project spawn visible —
        // it differs from the worker session's own project.
        assertThat(workers).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("count-md");
            assertThat(w.processId()).isEqualTo("w-1");
            assertThat(w.projectId()).isEqualTo("target-project");
            assertThat(w.status()).isEqualTo(ThinkProcessStatus.RUNNING);
        });
    }

    @Test
    void skipsTheLoopItselfAndClosedProcesses() {
        ThinkProcessDocument peer = process("peer-1", "trillian-user-loop", ThinkProcessStatus.IDLE);
        when(thinkProcessService.findBySession(TENANT, SESSION))
                .thenReturn(List.of(
                        peer,
                        process("w-done", "done-one", ThinkProcessStatus.CLOSED),
                        process("w-live", "live-one", ThinkProcessStatus.RUNNING)));

        List<TrillianInternalApi.TaskWorkerSnapshot> workers = api.listTaskWorkers(peer);

        assertThat(workers).singleElement().satisfies(w -> assertThat(w.name()).isEqualTo("live-one"));
    }

    private static ThinkProcessDocument process(String id, String name, ThinkProcessStatus status) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(id);
        doc.setName(name);
        doc.setTenantId(TENANT);
        doc.setSessionId(SESSION);
        doc.setProjectId("trillian-test");
        doc.setStatus(status);
        return doc;
    }
}
