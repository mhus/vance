package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.hactar.HactarTaskDocument;
import de.mhus.vance.shared.hactar.HactarTaskService;
import de.mhus.vance.shared.inbox.InboxItemAnsweredEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class HactarInboxCompletionListenerTest {

    private final HactarTaskService taskService = mock(HactarTaskService.class);
    private final HactarCompletionEventBus eventBus = mock(HactarCompletionEventBus.class);
    private final HactarInboxCompletionListener listener = new HactarInboxCompletionListener(
            taskService, eventBus, JsonMapper.builder().build());

    @Test
    void non_workflow_gate_item_is_ignored() {
        InboxItemDocument item = inboxItem("inbox-1", InboxItemType.APPROVAL, Map.of(/* no kind */));
        item.setAnswer(approvalAnswer(true));

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void approval_approved_yields_approved_outcome() {
        wireTask("inbox-1", "task-1");
        InboxItemDocument item = workflowGateItem("inbox-1", InboxItemType.APPROVAL);
        item.setAnswer(approvalAnswer(true));

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        TaskCompletedEvent ev = capture();
        assertThat(ev.outcome()).isEqualTo("approved");
        assertThat(ev.output().get("approved").asBoolean()).isTrue();
    }

    @Test
    void approval_rejected_yields_rejected_outcome() {
        wireTask("inbox-2", "task-2");
        InboxItemDocument item = workflowGateItem("inbox-2", InboxItemType.APPROVAL);
        item.setAnswer(approvalAnswer(false));

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        assertThat(capture().outcome()).isEqualTo("rejected");
    }

    @Test
    void decision_yields_chosen_option_as_outcome() {
        wireTask("inbox-3", "task-3");
        InboxItemDocument item = workflowGateItem("inbox-3", InboxItemType.DECISION);
        AnswerPayload answer = new AnswerPayload();
        answer.setOutcome(AnswerOutcome.DECIDED);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("chosen", "defer");
        answer.setValue(v);
        item.setAnswer(answer);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        assertThat(capture().outcome()).isEqualTo("defer");
    }

    @Test
    void feedback_yields_success_outcome() {
        wireTask("inbox-4", "task-4");
        InboxItemDocument item = workflowGateItem("inbox-4", InboxItemType.FEEDBACK);
        AnswerPayload answer = new AnswerPayload();
        answer.setOutcome(AnswerOutcome.DECIDED);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("text", "thanks");
        answer.setValue(v);
        item.setAnswer(answer);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        TaskCompletedEvent ev = capture();
        assertThat(ev.outcome()).isEqualTo("success");
        assertThat(ev.output().get("text").asString()).isEqualTo("thanks");
    }

    @Test
    void insufficient_info_propagates_outcome() {
        wireTask("inbox-5", "task-5");
        InboxItemDocument item = workflowGateItem("inbox-5", InboxItemType.APPROVAL);
        AnswerPayload answer = new AnswerPayload();
        answer.setOutcome(AnswerOutcome.INSUFFICIENT_INFO);
        answer.setReason("need more context");
        item.setAnswer(answer);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        TaskCompletedEvent ev = capture();
        assertThat(ev.outcome()).isEqualTo("insufficient_info");
        assertThat(ev.errorMessage()).isEqualTo("need more context");
    }

    @Test
    void undecidable_propagates_outcome() {
        wireTask("inbox-6", "task-6");
        InboxItemDocument item = workflowGateItem("inbox-6", InboxItemType.APPROVAL);
        AnswerPayload answer = new AnswerPayload();
        answer.setOutcome(AnswerOutcome.UNDECIDABLE);
        answer.setReason("conflicting signals");
        item.setAnswer(answer);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        assertThat(capture().outcome()).isEqualTo("undecidable");
    }

    @Test
    void answer_without_payload_yields_agent_error() {
        wireTask("inbox-7", "task-7");
        InboxItemDocument item = workflowGateItem("inbox-7", InboxItemType.APPROVAL);
        item.setAnswer(null);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        assertThat(capture().outcome()).isEqualTo("agent_error");
    }

    @Test
    void unlinked_workflow_gate_item_is_logged_and_skipped() {
        when(taskService.findByInboxItemId(eq("orphan"))).thenReturn(Optional.empty());
        InboxItemDocument item = workflowGateItem("orphan", InboxItemType.APPROVAL);
        item.setAnswer(approvalAnswer(true));

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(eventBus, never()).publish(any());
    }

    // ─────── helpers ───────

    private void wireTask(String inboxItemId, String taskId) {
        HactarTaskDocument task = HactarTaskDocument.builder()
                .id(taskId)
                .tenantId("acme").projectId("proj").workflowRunId("r1")
                .workflowName("demo").stateName("review")
                .inboxItemId(inboxItemId)
                .build();
        when(taskService.findByInboxItemId(eq(inboxItemId))).thenReturn(Optional.of(task));
    }

    private static InboxItemDocument workflowGateItem(String id, InboxItemType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", GateTaskExecutor.PAYLOAD_KIND);
        payload.put("workflowRunId", "r1");
        payload.put("workflowState", "review");
        return inboxItem(id, type, payload);
    }

    private static InboxItemDocument inboxItem(String id, InboxItemType type, Map<String, Object> payload) {
        InboxItemDocument doc = new InboxItemDocument();
        doc.setId(id);
        doc.setTenantId("acme");
        doc.setType(type);
        doc.setTitle("Approve?");
        doc.setPayload(payload);
        return doc;
    }

    private static AnswerPayload approvalAnswer(boolean approved) {
        AnswerPayload a = new AnswerPayload();
        a.setOutcome(AnswerOutcome.DECIDED);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("approved", approved);
        a.setValue(v);
        return a;
    }

    private TaskCompletedEvent capture() {
        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        return captor.getValue();
    }
}
