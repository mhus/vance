package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link VogonSessionLoop#splitInbox}: the [run]-note contract (decision F3).
 * The Magrathea runner wakes the owner through a pending ProcessEvent only —
 * a trigger, not a transcript — so the loop writes the durable history copy.
 * User input lands in the log exactly once, everything non-UCI stays
 * turn-local.
 */
class VogonSessionLoopSplitInboxTest {

    private static final ThinkProcessDocument PROCESS = process();

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        p.setTenantId("t1");
        p.setProjectId("p1");
        p.setSessionId("s1");
        return p;
    }

    private static SteerMessage.UserChatInput user(String fromUser, String content) {
        return new SteerMessage.UserChatInput(Instant.now(), null, fromUser, content);
    }

    private static SteerMessage.ProcessEvent runReports(ProcessEventType type, String summary) {
        return new SteerMessage.ProcessEvent(Instant.now(), null, "", type, summary, null, null, null);
    }

    @Test
    void realUserInputIsAppendedToTheChatLog() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("mara", "run the release plan")));

        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(1)).append(captor.capture());
        ChatMessageDocument saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("run the release plan");
        assertThat(saved.getThinkProcessId()).isEqualTo("proc-1");
        assertThat(saved.getRole()).isEqualTo(de.mhus.vance.api.chat.ChatRole.USER);
    }

    @Test
    void runEventsGetARunNoteAndStayTurnLocalExtras() {
        ChatMessageService chatLog = mock(ChatMessageService.class);
        SteerMessage.ProcessEvent event = runReports(ProcessEventType.DONE, "Workflow run done");

        List<SteerMessage> extras = VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("mara", "hi"), event));

        assertThat(extras).containsExactly(event);
        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        // Twice: the user's message, plus the [run] note for the event.
        verify(chatLog, times(2)).append(captor.capture());
        ChatMessageDocument note = captor.getAllValues().get(1);
        assertThat(note.getContent()).isEqualTo("[run] Workflow run done");
        assertThat(note.getRole()).isEqualTo(de.mhus.vance.api.chat.ChatRole.ASSISTANT);
        assertThat(note.getThinkProcessId()).isEqualTo("proc-1");
    }

    @Test
    void aRunEventWithoutASummaryStillGetsANote() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(runReports(ProcessEventType.BLOCKED, null)));

        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(1)).append(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("[run] run reported blocked");
    }

    @Test
    void aFailingNoteDoesNotKillTheSplit() {
        ChatMessageService chatLog = mock(ChatMessageService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("chat log down"))
                .when(chatLog)
                .append(any(ChatMessageDocument.class));
        SteerMessage.ProcessEvent event = runReports(ProcessEventType.DONE, "done");

        List<SteerMessage> extras = VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(event));

        assertThat(extras).containsExactly(event);
    }

    @Test
    void blankUserInputIsSkipped() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("mara", "  ")));

        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }

    @Test
    void toolResultsBecomeExtrasWithoutARunNote() {
        ChatMessageService chatLog = mock(ChatMessageService.class);
        SteerMessage.ToolResult toolResult =
                new SteerMessage.ToolResult(Instant.now(), null, "call-1", "doc_read", null, "payload", null);

        List<SteerMessage> extras = VogonSessionLoop.splitInbox(chatLog, PROCESS, List.of(toolResult));

        assertThat(extras).containsExactly(toolResult);
        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }
}
