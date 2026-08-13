package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task events all leave through one dispatch funnel, which is where a
 * Nature gets told that a task concluded. Only conclusions count, and the
 * telling must never come before the event that Control is waiting for.
 */
class TrillianTaskConclusionTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String WORKER = "worker-proc";
    private static final String CONTROL = "control-proc";

    private ThinkProcessService thinkProcessService;
    private EngineMessageRouter messageRouter;
    private TrillianNature nature;
    private TrillianInternalApi api;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        messageRouter = mock(EngineMessageRouter.class);
        nature = mock(TrillianNature.class);
        when(nature.id()).thenReturn("adam");
        when(thinkProcessService.findById(WORKER)).thenReturn(Optional.of(worker()));
        when(messageRouter.dispatch(anyString(), anyString(), any())).thenReturn(true);
        api = new TrillianInternalApi(
                thinkProcessService,
                messageRouter,
                mock(EngineMessageService.class),
                mock(ProcessEventEmitter.class),
                mock(ChatMessageService.class),
                mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
                new TrillianNatureRegistry(List.of(nature)));
    }

    @Test
    void aDoneTask_reachesTheNature() {
        dispatch(TrillianInternalApi.TASK_EVENT_DONE, "task-1", "Task done: counted 17");

        verify(nature).taskConcluded(any(ThinkProcessDocument.class), eq("task-1"),
                eq(TrillianNature.TaskOutcome.DONE), anyString());
    }

    @Test
    void aFailedTask_reachesTheNature() {
        dispatch(TrillianInternalApi.TASK_EVENT_FAILED, "task-2", "Task failed: no access");

        verify(nature).taskConcluded(any(ThinkProcessDocument.class), eq("task-2"),
                eq(TrillianNature.TaskOutcome.FAILED), anyString());
    }

    @Test
    void aRequestOrAQuestion_isNoConclusion() {
        // Reflecting on a task that just started, or on a question the
        // worker asked, would teach it nothing except that it asked one.
        dispatch(TrillianInternalApi.TASK_EVENT_REQUEST, "task-3", "please do X");
        dispatch(TrillianInternalApi.TASK_EVENT_NEEDS_INPUT, "task-3", "which folder?");

        verify(nature, never()).taskConcluded(any(), any(), any(), any());
    }

    @Test
    void aFailedDispatch_tellsTheNatureNothing() {
        // Control never heard the outcome, so there is no conclusion to
        // reflect on — and a journal entry about it would be a lie.
        when(messageRouter.dispatch(anyString(), anyString(), any())).thenReturn(false);

        dispatch(TrillianInternalApi.TASK_EVENT_DONE, "task-4", "Task done: x");

        verify(nature, never()).taskConcluded(any(), any(), any(), any());
    }

    @Test
    void aThrowingNature_doesNotCostTheEvent() {
        org.mockito.Mockito.doThrow(new IllegalStateException("model down"))
                .when(nature).taskConcluded(any(), any(), any(), any());

        Optional<String> eventId =
                dispatch(TrillianInternalApi.TASK_EVENT_DONE, "task-5", "Task done: x");

        // The tool call that reports the result must still succeed.
        org.assertj.core.api.Assertions.assertThat(eventId).isPresent();
    }

    private Optional<String> dispatch(String event, String taskId, String summary) {
        return api.dispatchTaskEvent(WORKER, CONTROL, event, taskId, summary, Map.of());
    }

    private static ThinkProcessDocument worker() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(WORKER);
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, "adam");
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, "_trillian-adam-4711");
        p.setEngineParams(params);
        return p;
    }
}
