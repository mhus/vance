package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MagratheaGateChatAnswerServiceTest {

    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final InboxItemService inboxItemService = mock(InboxItemService.class);

    private final MagratheaGateChatAnswerService service =
            new MagratheaGateChatAnswerService(taskService, inboxItemService);

    private static MagratheaTaskDocument waitingTask() {
        return MagratheaTaskDocument.builder()
                .id("task-1")
                .tenantId("t")
                .projectId("p")
                .workflowRunId("run-1")
                .stateName("ask")
                .taskType(MagratheaTaskType.GATE_TASK)
                .status(MagratheaTaskStatus.CLAIMED)
                .runStatus(MagratheaTaskRunStatus.WAITING_INBOX)
                .inboxItemId("item-1")
                .build();
    }

    private static InboxItemDocument pendingItem(InboxItemType type, Map<String, Object> payload) {
        return InboxItemDocument.builder()
                .id("item-1")
                .tenantId("t")
                .type(type)
                .status(InboxItemStatus.PENDING)
                .title("ok?")
                .payload(payload)
                .build();
    }

    @Test
    void tryAnswer_readableApproval_writesTheInboxAnswer() {
        when(taskService.findByRun("run-1")).thenReturn(List.of(waitingTask()));
        when(inboxItemService.findById("t", "item-1"))
                .thenReturn(Optional.of(pendingItem(InboxItemType.APPROVAL, Map.of())));

        boolean answered = service.tryAnswer("t", "run-1", "ja", "alice");

        assertThat(answered).isTrue();
        ArgumentCaptor<AnswerPayload> captor = ArgumentCaptor.forClass(AnswerPayload.class);
        verify(inboxItemService).answer(eq("t"), eq("item-1"), captor.capture(),
                eq(ResolvedBy.USER));
        assertThat(captor.getValue().getValue()).containsEntry("approved", true);
    }

    @Test
    void tryAnswer_unreadableText_leavesTheGateOpen() {
        when(taskService.findByRun("run-1")).thenReturn(List.of(waitingTask()));
        when(inboxItemService.findById("t", "item-1"))
                .thenReturn(Optional.of(pendingItem(InboxItemType.APPROVAL, Map.of())));

        boolean answered = service.tryAnswer("t", "run-1", "what would that change?", "alice");

        assertThat(answered).isFalse();
        verify(inboxItemService, never()).answer(any(), any(), any(), any());
    }

    @Test
    void tryAnswer_noWaitingGate_isANoOp() {
        when(taskService.findByRun("run-1")).thenReturn(List.of());

        assertThat(service.tryAnswer("t", "run-1", "ja", "alice")).isFalse();
        verify(inboxItemService, never()).answer(any(), any(), any(), any());
    }

    @Test
    void tryAnswer_taskWaitingOnSomethingElse_isIgnored() {
        MagratheaTaskDocument onAnAgent = MagratheaTaskDocument.builder()
                .id("task-2").tenantId("t").projectId("p").workflowRunId("run-1")
                .stateName("work").taskType(MagratheaTaskType.AGENT_TASK)
                .status(MagratheaTaskStatus.CLAIMED)
                .runStatus(MagratheaTaskRunStatus.WAITING_SUBPROCESS)
                .build();
        when(taskService.findByRun("run-1")).thenReturn(List.of(onAnAgent));

        assertThat(service.tryAnswer("t", "run-1", "ja", "alice")).isFalse();
    }

    @Test
    void tryAnswer_itemAlreadyAnswered_isIgnored() {
        // Someone used the form a moment earlier; there is nothing open.
        when(taskService.findByRun("run-1")).thenReturn(List.of(waitingTask()));
        InboxItemDocument answered = pendingItem(InboxItemType.APPROVAL, Map.of());
        answered.setStatus(InboxItemStatus.ANSWERED);
        when(inboxItemService.findById("t", "item-1")).thenReturn(Optional.of(answered));

        assertThat(service.tryAnswer("t", "run-1", "ja", "alice")).isFalse();
        verify(inboxItemService, never()).answer(any(), any(), any(), any());
    }

    @Test
    void tryAnswer_decisionUsesTheDeclaredOptions() {
        when(taskService.findByRun("run-1")).thenReturn(List.of(waitingTask()));
        when(inboxItemService.findById("t", "item-1")).thenReturn(Optional.of(
                pendingItem(InboxItemType.DECISION,
                        Map.of("options", List.of("retry", "abort")))));

        assertThat(service.tryAnswer("t", "run-1", "abort", "alice")).isTrue();

        ArgumentCaptor<AnswerPayload> captor = ArgumentCaptor.forClass(AnswerPayload.class);
        verify(inboxItemService).answer(any(), any(), captor.capture(), any());
        assertThat(captor.getValue().getValue()).containsEntry("chosen", "abort");
    }
}
