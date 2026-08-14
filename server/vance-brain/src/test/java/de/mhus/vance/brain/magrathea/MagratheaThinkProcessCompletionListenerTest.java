package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MagratheaThinkProcessCompletionListenerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);

    private final de.mhus.vance.shared.magrathea.MagratheaJournalService journalService =
            mock(de.mhus.vance.shared.magrathea.MagratheaJournalService.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.brain.enginemessage.EngineMessageRouter> messageRouterProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);

    private final MagratheaThinkProcessCompletionListener listener =
            new MagratheaThinkProcessCompletionListener(
                    taskService, eventBus, thinkProcessService,
                    chatMessageService, objectMapper,
                    journalService, messageRouterProvider);

    @Test
    void non_terminal_status_change_is_ignored() {
        listener.onStatusChanged(event(ThinkProcessStatus.RUNNING));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void turnEnd_toIdle_completesTheTask_withTheReplyAsOutput() {
        // Ford & co. never close themselves; the finished turn is the
        // completion signal, or the run waits forever.
        wireRunningTask("p-turn", "here is the analysis");

        listener.onStatusChanged(turnEnd("p-turn", ThinkProcessStatus.IDLE));

        var ev = org.mockito.ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus).publish(ev.capture());
        assertThat(ev.getValue().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(ev.getValue().output().asString()).isEqualTo("here is the analysis");
        // Closed by us, and unlinked first so the CLOSED event stays quiet.
        verify(taskService).unlinkSubProcess("task-p-turn");
        verify(thinkProcessService).closeProcess("p-turn", CloseReason.DONE);
    }

    @Test
    void turnEnd_toBlocked_reportsNeedsInput_soTheWorkflowCanAskAHuman() {
        wireRunningTask("p-ask", "which environment should I deploy to?");

        listener.onStatusChanged(turnEnd("p-ask", ThinkProcessStatus.BLOCKED));

        var ev = org.mockito.ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus).publish(ev.capture());
        assertThat(ev.getValue().outcome())
                .isEqualTo(MagratheaThinkProcessCompletionListener.OUTCOME_NEEDS_INPUT);
        assertThat(ev.getValue().output().asString())
                .isEqualTo("which environment should I deploy to?");
        verify(thinkProcessService).closeProcess("p-ask", CloseReason.INCOMPLETE);
    }

    @Test
    void startupIdle_isNotATurnEnd() {
        // INIT -> IDLE happens on every spawn. Treating it as completion
        // would finish every agent_task before the agent did anything.
        wireRunningTask("p-boot", "unused");

        listener.onStatusChanged(new ThinkProcessStatusChangedEvent(
                "p-boot", "acme", "sess-1", null,
                ThinkProcessStatus.INIT, ThinkProcessStatus.IDLE));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void unlinked_process_close_is_ignored() {
        when(taskService.findBySubProcessId(eq("proc-x"))).thenReturn(Optional.empty());

        listener.onStatusChanged(event(ThinkProcessStatus.CLOSED));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void reconcile_returns_false_and_publishes_nothing_when_process_still_running() {
        // Crash-recovery guard: the scanner may hand reconcile() a task
        // whose subprocess is still alive — that must be a no-op.
        MagratheaTaskDocument task = task("p-running", "ford");
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p-running");
        process.setStatus(ThinkProcessStatus.RUNNING);
        when(thinkProcessService.findById(eq("p-running"))).thenReturn(Optional.of(process));

        boolean reconciled = listener.reconcile(task, "p-running");

        assertThat(reconciled).isFalse();
        verify(eventBus, never()).publish(any());
    }

    @Test
    void jeltz_wrapper_success_yields_success_outcome_with_data_output() {
        wireTask("ford-jeltz", "jeltz");
        wireChatHistory("ford-jeltz",
                "{\"success\":true,\"attempts\":1,\"data\":{\"risk\":\"low\"}}");

        listener.onStatusChanged(event("ford-jeltz", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(ev.output().get("risk").asString()).isEqualTo("low");
    }

    @Test
    void jeltz_wrapper_failure_yields_agent_error_with_lastInvalid_output() {
        wireTask("p-fail", "jeltz");
        wireChatHistory("p-fail",
                "{\"success\":false,\"attempts\":3,\"error\":\"schema_violation\","
                        + "\"message\":\"missing 'risk'\",\"lastInvalid\":{\"foo\":\"bar\"}}");

        listener.onStatusChanged(event("p-fail", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("agent_error");
        assertThat(ev.output().get("foo").asString()).isEqualTo("bar");
        assertThat(ev.errorMessage()).contains("schema_violation").contains("missing");
    }

    @Test
    void jeltz_unparseable_body_yields_agent_error() {
        wireTask("p-bad", "jeltz");
        wireChatHistory("p-bad", "not json at all");

        listener.onStatusChanged(event("p-bad", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("agent_error");
        assertThat(ev.errorMessage()).contains("not a JSON object");
    }

    @Test
    void jeltz_no_assistant_message_yields_agent_error() {
        wireTask("p-empty", "jeltz");
        when(chatMessageService.history(any(), any(), eq("p-empty")))
                .thenReturn(List.of());

        listener.onStatusChanged(event("p-empty", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("agent_error");
        assertThat(ev.errorMessage()).contains("without an assistant message");
    }

    @Test
    void ford_close_yields_success_with_last_assistant_text() {
        wireTask("p-ford", "ford");
        wireChatHistory("p-ford", "the final answer is 42");

        listener.onStatusChanged(event("p-ford", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(ev.output().asString()).isEqualTo("the final answer is 42");
    }

    @Test
    void stale_close_yields_technical_error() {
        wireTask("p-stale", "ford", CloseReason.STALE);

        listener.onStatusChanged(event("p-stale", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("technical_error");
        assertThat(ev.errorMessage()).contains("STALE");
    }

    @Test
    void stopped_close_yields_cancelled() {
        wireTask("p-stop", "ford", CloseReason.STOPPED);

        listener.onStatusChanged(event("p-stop", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("cancelled");
    }

    @Test
    void null_close_reason_yields_technical_error() {
        wireTask("p-null", "ford", null);

        listener.onStatusChanged(event("p-null", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("technical_error");
    }

    @Test
    void missing_process_document_yields_technical_error() {
        MagratheaTaskDocument task = task("p-vanished", "ford");
        when(taskService.findBySubProcessId(eq("p-vanished"))).thenReturn(Optional.of(task));
        when(thinkProcessService.findById(eq("p-vanished"))).thenReturn(Optional.empty());

        listener.onStatusChanged(event("p-vanished", ThinkProcessStatus.CLOSED));

        TaskCompletedEvent ev = capturePublished();
        assertThat(ev.outcome()).isEqualTo("technical_error");
        assertThat(ev.errorMessage()).contains("document not found");
    }

    // ─────── helpers ───────

    private static ThinkProcessStatusChangedEvent event(ThinkProcessStatus status) {
        return event("proc-1", status);
    }

    private static ThinkProcessStatusChangedEvent event(String processId, ThinkProcessStatus status) {
        return new ThinkProcessStatusChangedEvent(
                processId, "acme", "sess-1", null, null, status);
    }

    /** Turn-end event: the prior status is what separates "finished a turn"
     *  from "started up and has not worked yet". */
    private static ThinkProcessStatusChangedEvent turnEnd(
            String processId, ThinkProcessStatus status) {
        return new ThinkProcessStatusChangedEvent(
                processId, "acme", "sess-1", null, ThinkProcessStatus.RUNNING, status);
    }

    /** A live (non-closed) agent process with one assistant reply. */
    private void wireRunningTask(String processId, String reply) {
        when(taskService.findBySubProcessId(eq(processId)))
                .thenReturn(Optional.of(task(processId, "ford")));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(processId);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setThinkEngine("ford");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        process.setUpdatedAt(Instant.parse("2024-01-01T00:00:04Z"));
        when(thinkProcessService.findById(eq(processId))).thenReturn(Optional.of(process));
        ChatMessageDocument msg = new ChatMessageDocument();
        msg.setRole(ChatRole.ASSISTANT);
        msg.setContent(reply);
        when(chatMessageService.history(eq("acme"), eq("sess-1"), eq(processId)))
                .thenReturn(List.of(msg));
    }

    private void wireTask(String processId, String engineName) {
        wireTask(processId, engineName, CloseReason.DONE);
    }

    private void wireTask(String processId, String engineName, CloseReason closeReason) {
        MagratheaTaskDocument task = task(processId, engineName);
        when(taskService.findBySubProcessId(eq(processId))).thenReturn(Optional.of(task));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(processId);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setThinkEngine(engineName);
        process.setStatus(ThinkProcessStatus.CLOSED);
        process.setCloseReason(closeReason);
        process.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        process.setUpdatedAt(Instant.parse("2024-01-01T00:00:05Z"));
        when(thinkProcessService.findById(eq(processId))).thenReturn(Optional.of(process));
        // Override the default 'proc-1' event id so each test addresses
        // its own process — keeping the same status helper otherwise.
    }

    private static MagratheaTaskDocument task(String subProcessId, String engineName) {
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .id("task-" + subProcessId)
                .tenantId("acme")
                .projectId("proj")
                .workflowRunId("r1")
                .workflowName("demo")
                .stateName("plan")
                .subProcessId(subProcessId)
                .build();
        return task;
    }

    private void wireChatHistory(String processId, String assistantBody) {
        ChatMessageDocument userMsg = new ChatMessageDocument();
        userMsg.setRole(ChatRole.USER);
        userMsg.setContent("question");
        userMsg.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        ChatMessageDocument assistantMsg = new ChatMessageDocument();
        assistantMsg.setRole(ChatRole.ASSISTANT);
        assistantMsg.setContent(assistantBody);
        assistantMsg.setCreatedAt(Instant.parse("2024-01-01T00:00:01Z"));
        when(chatMessageService.history(any(), any(), eq(processId)))
                .thenReturn(List.of(userMsg, assistantMsg));
    }

    private TaskCompletedEvent capturePublished() {
        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        return captor.getValue();
    }
}
