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

    private Map<String, Object> ask(String question) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(PROC);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        when(processes.findById(PROC)).thenReturn(Optional.of(process));
        ToolInvocationContext ctx = mock(ToolInvocationContext.class);
        when(ctx.processId()).thenReturn(PROC);
        return new TrillianAskTool(processes, chat, progress)
                .invoke(Map.of("question", question), ctx);
    }
}
