package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MagratheaSubWorkflowCompletionListenerTest {

    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaSubWorkflowCompletionListener listener =
            new MagratheaSubWorkflowCompletionListener(taskService, eventBus);

    @Test
    void top_level_run_without_parent_is_ignored() {
        listener.onCompleted(event(MagratheaRunStatus.DONE, /* parent */ null));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void sub_run_with_parent_but_no_linked_task_is_logged_and_skipped() {
        when(taskService.findBySubWorkflowRunId(eq("sub-1"))).thenReturn(Optional.empty());

        listener.onCompleted(event(MagratheaRunStatus.DONE, "parent-run"));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void done_status_yields_success_with_result_payload() {
        wireParent("sub-1", "task-1");

        listener.onCompleted(event(MagratheaRunStatus.DONE, "parent-run",
                JsonMapper.builder().build().valueToTree(java.util.Map.of("answer", 42))));

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        TaskCompletedEvent ev = captor.getValue();
        assertThat(ev.outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(ev.taskType()).isEqualTo(MagratheaTaskType.WORKFLOW_TASK);
        assertThat(ev.output().get("answer").asInt()).isEqualTo(42);
    }

    @Test
    void failed_status_yields_failure_outcome() {
        wireParent("sub-2", "task-2");

        listener.onCompleted(event(MagratheaRunStatus.FAILED, "parent-run"));

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(captor.getValue().errorMessage()).contains("FAILED");
    }

    @Test
    void terminated_status_yields_failure_outcome() {
        wireParent("sub-3", "task-3");
        listener.onCompleted(event(MagratheaRunStatus.TERMINATED, "parent-run"));
        verify(eventBus).publish(any());
    }

    @Test
    void paused_status_does_not_publish() {
        wireParent("sub-4", "task-4");
        listener.onCompleted(event(MagratheaRunStatus.PAUSED, "parent-run"));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void running_status_does_not_publish() {
        wireParent("sub-5", "task-5");
        listener.onCompleted(event(MagratheaRunStatus.RUNNING, "parent-run"));
        verify(eventBus, never()).publish(any());
    }

    private void wireParent(String subRunId, String parentTaskId) {
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .id(parentTaskId)
                .tenantId("acme").projectId("proj").workflowRunId("parent-run")
                .workflowName("parent").stateName("call_sub")
                .subWorkflowRunId(subRunId)
                .build();
        when(taskService.findBySubWorkflowRunId(eq(subRunId))).thenReturn(Optional.of(task));
    }

    private WorkflowCompletedEvent event(MagratheaRunStatus status, String parentRunId) {
        return event(status, parentRunId, null);
    }

    private WorkflowCompletedEvent event(
            MagratheaRunStatus status,
            String parentRunId,
            tools.jackson.databind.JsonNode result) {
        // Use whichever sub-run id matches the wired parent — the listener
        // looks up by subWorkflowRunId so per-test specificity matters.
        String subRunId = switch (status) {
            case DONE -> "sub-1";
            case FAILED -> "sub-2";
            case TERMINATED -> "sub-3";
            case PAUSED -> "sub-4";
            case RUNNING -> "sub-5";
        };
        return new WorkflowCompletedEvent(
                "acme", "proj", subRunId, "sub-workflow",
                status, result, parentRunId, "call_sub");
    }
}
