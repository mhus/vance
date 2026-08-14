package de.mhus.vance.brain.vogon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * How a Vogon process ends when the run below it does.
 */
class VogonEngineCloseTest {

    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaGateChatAnswerService gateAnswers =
            mock(MagratheaGateChatAnswerService.class);
    private final VogonIntake intake = mock(VogonIntake.class);
    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final SessionService sessions = mock(SessionService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progress =
            mock(ObjectProvider.class);

    private final VogonEngine engine = new VogonEngine(
            workflowService, projector, gateAnswers, intake, processes, sessions, progress);

    private static ThinkProcessDocument delegatedProcess() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("vogon-1");
        p.setTenantId("t");
        p.setProjectId("p");
        p.setSessionId("sess-1");
        p.setParentProcessId("arthur-1");
        return p;
    }

    private static SteerMessage.ProcessEvent runReports(ProcessEventType type) {
        return new SteerMessage.ProcessEvent(
                Instant.now(), null, "", type, "the run says so", null, null, null);
    }

    @Test
    void onDone_theParentsWatchIsReleasedBeforeTheProcessCloses() {
        // The lifecycle event is suppressed while the parent holds a worker
        // link, on the assumption that the news travels over a Working WS.
        // Vogon streams nothing, so for it that assumption means the news
        // travels nowhere: the parent would keep pointing at a closed
        // process. Dropping the link has to happen first — afterwards the
        // event is already on its way out, suppressed.
        engine.steer(delegatedProcess(), null, runReports(ProcessEventType.DONE));

        var order = inOrder(processes);
        order.verify(processes).removeWorkerLink("arthur-1", "vogon-1");
        order.verify(processes).closeProcess("vogon-1", CloseReason.DONE);
    }

    @Test
    void onFailed_theSameHappens() {
        engine.steer(delegatedProcess(), null, runReports(ProcessEventType.FAILED));

        var order = inOrder(processes);
        order.verify(processes).removeWorkerLink("arthur-1", "vogon-1");
        order.verify(processes).closeProcess("vogon-1", CloseReason.STALE);
    }

    @Test
    void withoutAParent_thereIsNoWatchToRelease() {
        ThinkProcessDocument orphan = delegatedProcess();
        orphan.setParentProcessId(null);

        engine.steer(orphan, null, runReports(ProcessEventType.DONE));

        verify(processes, never()).removeWorkerLink(any(), any());
        verify(processes).closeProcess("vogon-1", CloseReason.DONE);
    }

    @Test
    void aFailingLinkRemovalDoesNotStopTheClose() {
        // Better a parent that still points at a closed process than a
        // process that never closes at all.
        org.mockito.Mockito.doThrow(new IllegalStateException("mongo down"))
                .when(processes).removeWorkerLink(any(), any());

        engine.steer(delegatedProcess(), null, runReports(ProcessEventType.DONE));

        verify(processes).closeProcess("vogon-1", CloseReason.DONE);
    }

    @Test
    void blockedOnlyChangesTheStatus() {
        engine.steer(delegatedProcess(), null, runReports(ProcessEventType.BLOCKED));

        verify(processes).updateStatus(eq("vogon-1"), any());
        verify(processes, never()).closeProcess(any(), any());
        verify(processes, never()).removeWorkerLink(any(), any());
    }
}
