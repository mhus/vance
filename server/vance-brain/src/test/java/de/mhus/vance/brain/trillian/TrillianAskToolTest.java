package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.frankie.FrankieTermination;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.trillian.tools.TrillianAskTool;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Asking has to leave three traces, and the live run proved each one is
 * load-bearing: the marker (or the engine closes the worker), the push
 * (or nobody hears the question), and the receipt (or the worker thinks
 * the attempt failed and asks again).
 */
class TrillianAskToolTest {

    private static final String PROC = "worker-1";

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final ChatMessageService chat = mock(ChatMessageService.class);
    private final ProgressEmitter progress = mock(ProgressEmitter.class);

    @Test
    void itMarksTheProcess_soTheEngineParksInsteadOfClosing() {
        ask("Which destination should I use?");

        verify(processes).setEngineParamOverride(
                PROC, TrillianWorkerEngine.PARAM_ASK_PENDING, true);
    }

    @Test
    void itPushesTheQuestionToTheParent() {
        // Frankie routes a worker's words to its parent from the
        // natural-stop path only, and this exit is not that path.
        ask("Which destination should I use?");

        verify(progress).emitReply(any(), org.mockito.ArgumentMatchers.eq(
                "Which destination should I use?"), any(), any());
    }

    @Test
    void itLeavesAReceipt_soTheNextTurnKnowsItAlreadyAsked() {
        ask("Which destination should I use?");

        ArgumentCaptor<ChatMessageDocument> messages =
                ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chat, org.mockito.Mockito.times(2)).append(messages.capture());
        List<ChatMessageDocument> written = messages.getAllValues();

        // The question itself, then the receipt — and the receipt has to
        // come from the side the answer will come from, or the model
        // reads it as another thought of its own.
        assertThat(written.get(0).getContent()).isEqualTo("Which destination should I use?");
        assertThat(written.get(1).getRole()).isEqualTo(ChatRole.USER);
        assertThat(written.get(1).getContent()).contains("Do not ask it again");
    }

    @Test
    void itLeavesTheLoop() {
        // Same exit as trillian_done; only the consequence differs.
        assertThat(ask("anything")).containsEntry(FrankieTermination.RESULT_TERMINATE_KEY, true);
    }

    @Test
    void aDifferentQuestion_getsAFreshRecheckBudget() {
        // The worker spent its probes on one obstacle, was answered,
        // carried on and met another. Inheriting the exhausted breaker
        // would deny the new obstacle the cheap re-check it deserves —
        // the only thing left would be the two-hour probe.
        ThinkProcessDocument worker = worker();
        ask(worker, "The database is locked — should I wait?");
        worker.getEngineParamOverrides().put(TrillianAskTool.PARAM_ASK_PROBES, 3);
        worker.getEngineParamOverrides().put(
                TrillianAskTool.PARAM_ASK_OPENED_AT, 1_700_000_000_000L);
        org.mockito.Mockito.clearInvocations(processes);

        ask(worker, "The export directory does not exist — create it?");

        verify(processes).setEngineParamOverride(PROC, TrillianAskTool.PARAM_ASK_PROBES, null);
        verify(processes).setEngineParamOverride(PROC, TrillianAskTool.PARAM_ASK_OPENED_AT, null);
    }

    @Test
    void theSameQuestionComingBack_keepsItsBudget() {
        // That is the case the breaker exists for: rounds that changed
        // nothing. Resetting on every re-ask would make it never open, and
        // the nudge would repeat for as long as the worker lives.
        ThinkProcessDocument worker = worker();
        ask(worker, "The database is locked — should I wait?");
        worker.getEngineParamOverrides().put(TrillianAskTool.PARAM_ASK_PROBES, 2);
        org.mockito.Mockito.clearInvocations(processes);

        // Same question, re-typed: whitespace and case are not a new question.
        ask(worker, "  the DATABASE is locked — should I   wait? ");

        verify(processes, org.mockito.Mockito.never())
                .setEngineParamOverride(PROC, TrillianAskTool.PARAM_ASK_PROBES, null);
    }

    private Map<String, Object> ask(String question) {
        return ask(worker(), question);
    }

    /**
     * A worker whose overrides actually change when the tool writes them —
     * the episode logic reads back what it wrote, so a mock that forgets
     * would make every ask look like the first.
     */
    private ThinkProcessDocument worker() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(PROC);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setEngineParamOverrides(new java.util.LinkedHashMap<>());
        org.mockito.Mockito.doAnswer(inv -> {
            Object value = inv.getArgument(2);
            if (value == null) {
                process.getEngineParamOverrides().remove(inv.<String>getArgument(1));
            } else {
                process.getEngineParamOverrides().put(inv.getArgument(1), value);
            }
            return true;
        }).when(processes).setEngineParamOverride(
                org.mockito.ArgumentMatchers.eq(PROC),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        return process;
    }

    private Map<String, Object> ask(ThinkProcessDocument process, String question) {
        when(processes.findById(PROC)).thenReturn(Optional.of(process));
        ToolInvocationContext ctx = mock(ToolInvocationContext.class);
        when(ctx.processId()).thenReturn(PROC);
        return new TrillianAskTool(processes, chat, progress)
                .invoke(Map.of("question", question), ctx);
    }
}
