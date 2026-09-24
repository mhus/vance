package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link HactarSessionLoop#splitInbox}: the wakeup-dedup contract of the
 * chat-log append (mirrors {@code WowbaggerEngineSplitInboxTest}). The run
 * service writes its "[run]" note to the history itself and sends the
 * pending copy only as the lane-wake trigger — the engine must not append
 * it again as a USER message.
 */
class HactarSessionLoopSplitInboxTest {

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

    @Test
    void realUserInputIsAppendedToTheChatLog() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        HactarSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("marvin", "run the mail bot")));

        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(1)).append(captor.capture());
        ChatMessageDocument saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("run the mail bot");
        assertThat(saved.getThinkProcessId()).isEqualTo("proc-1");
        assertThat(saved.getRole()).isEqualTo(de.mhus.vance.api.chat.ChatRole.USER);
    }

    @Test
    void runWakeupsAreNotAppendedTwice() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        // The run service's wakeup: fromUser is the run sender, the note text
        // is already in the history (written by HactarRunService.wakeup).
        HactarSessionLoop.splitInbox(
                chatLog, PROCESS, List.of(user(HactarRunService.WAKEUP_SENDER, "[run] run finished — 'x.js'")));

        // The drained copy is only the trigger — no second history entry.
        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }

    @Test
    void blankUserInputIsSkipped() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        HactarSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("marvin", "  ")));

        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }

    @Test
    void nonUserChatItemsBecomeExtras() {
        ChatMessageService chatLog = mock(ChatMessageService.class);
        SteerMessage.ProcessEvent event = new SteerMessage.ProcessEvent(
                java.time.Instant.now(),
                null,
                "child-1",
                de.mhus.vance.api.thinkprocess.ProcessEventType.DONE,
                "child finished",
                null,
                null,
                null);

        List<SteerMessage> extras =
                HactarSessionLoop.splitInbox(chatLog, PROCESS, List.of(user("marvin", "hi"), event));

        assertThat(extras).containsExactly(event);
        // Only the user message lands in the log, the event stays turn-local.
        verify(chatLog, times(1)).append(any(ChatMessageDocument.class));
    }
}
