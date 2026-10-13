package de.mhus.vance.brain.wowbagger;

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
 * {@link WowbaggerEngine#splitInbox}: the wakeup-dedup contract of the
 * chat-log append (Code-Review 15, M2). The pool writes its "[pool]" note to
 * the history itself and sends the pending copy only as the lane-wake
 * trigger — the engine must not append it again as a USER message.
 */
class WowbaggerEngineSplitInboxTest {

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

        WowbaggerEngine.splitInbox(chatLog, PROCESS, List.of(user("marvin", "classify these records")));

        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(1)).append(captor.capture());
        ChatMessageDocument saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("classify these records");
        assertThat(saved.getThinkProcessId()).isEqualTo("proc-1");
        assertThat(saved.getRole()).isEqualTo(de.mhus.vance.api.chat.ChatRole.USER);
    }

    @Test
    void poolWakeupsAreNotAppendedTwice() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        // The pool's wakeup: fromUser is the pool sender, the note text is
        // already in the history (written by WowbaggerPoolService.wakeup).
        WowbaggerEngine.splitInbox(
                chatLog,
                PROCESS,
                List.of(user(WowbaggerPoolService.WAKEUP_SENDER, "[pool] progress: 100 of 1000 records")));

        // The drained copy is only the trigger — no second history entry.
        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }

    @Test
    void blankUserInputIsSkipped() {
        ChatMessageService chatLog = mock(ChatMessageService.class);

        WowbaggerEngine.splitInbox(chatLog, PROCESS, List.of(user("marvin", "  ")));

        verify(chatLog, never()).append(any(ChatMessageDocument.class));
    }

    @Test
    void nonUserItemsBecomeTurnLocalExtras() {
        ChatMessageService chatLog = mock(ChatMessageService.class);
        SteerMessage.ProcessEvent event = new SteerMessage.ProcessEvent(
                Instant.now(), null, "child-1", ProcessEventType.SUMMARY, "child finished", null, null, null);

        List<SteerMessage> extras =
                WowbaggerEngine.splitInbox(chatLog, PROCESS, List.of(user("marvin", "hello"), event));

        assertThat(extras).containsExactly(event);
        verify(chatLog, times(1)).append(any(ChatMessageDocument.class));
    }

    @Test
    void triggerText_isTheRealUserInput_neverPoolWakeups() {
        // A wakeup like "[pool] … failed" must not fire a word-trigger skill —
        // only what the user typed counts.
        String text = WowbaggerEngine.userText(List.of(
                user(WowbaggerPoolService.WAKEUP_SENDER, "[pool] debug: chunk 3 failed"),
                user("marvin", "classify these records"),
                user("marvin", "  "),
                user("marvin", "and decide the labels")));

        org.assertj.core.api.Assertions.assertThat(text).isEqualTo("classify these records\nand decide the labels");
    }
}
