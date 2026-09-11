package de.mhus.vance.brain.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * The live-push side of the machinery-process filter: a
 * {@code silent} process (e.g. a Zaphod session head) persists its
 * transcript but must not narrate itself into the chat — no
 * {@code CHAT_MESSAGE_APPENDED} frame. The scrollback filter
 * (ChatHistoryController.excludeSilent) hides the same messages on
 * reload; without the live filter the head notes would flash while
 * the session is open and vanish only on reload.
 */
class ChatMessageNotificationDispatcherSilentTest {

    private final SessionConnectionRegistry connectionRegistry = mock(SessionConnectionRegistry.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final WebSocketSender sender = mock(WebSocketSender.class);
    private final ChatMessageNotificationDispatcher dispatcher =
            new ChatMessageNotificationDispatcher(connectionRegistry, thinkProcessService, sender);

    @Test
    void silentProcessMessage_isNotPushed() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(connectionRegistry.findAll("s1")).thenReturn(List.of(ws));
        ThinkProcessDocument head = ThinkProcessDocument.builder()
                .id("head-1")
                .tenantId("t")
                .sessionId("s1")
                .name("zaphod-p1-optimist")
                .thinkEngine("ford")
                .silent(true)
                .build();
        when(thinkProcessService.findById("head-1")).thenReturn(Optional.of(head));

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(msg("head-1", ChatRole.ASSISTANT)));

        verify(sender, never()).sendNotification(any(), any(), any());
    }

    @Test
    void visibleWorkerMessage_isPushedWithProcessName() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(connectionRegistry.findAll("s1")).thenReturn(List.of(ws));
        ThinkProcessDocument worker = ThinkProcessDocument.builder()
                .id("w-1")
                .tenantId("t")
                .sessionId("s1")
                .name("frankie-run")
                .thinkEngine("frankie")
                .build(); // silent defaults to false
        when(thinkProcessService.findById("w-1")).thenReturn(Optional.of(worker));

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(msg("w-1", ChatRole.ASSISTANT)));

        verify(sender).sendNotification(eq(ws), eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @Test
    void unknownProcess_fallsBackToPush() throws Exception {
        // A message whose process row is gone (mid-teardown) must not
        // silently swallow the frame — fail-open like the rest of the
        // dispatcher, the scrollback stays the source of truth.
        WebSocketSession ws = mock(WebSocketSession.class);
        when(connectionRegistry.findAll("s1")).thenReturn(List.of(ws));
        when(thinkProcessService.findById("gone")).thenReturn(Optional.empty());

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(msg("gone", ChatRole.ASSISTANT)));

        verify(sender).sendNotification(eq(ws), eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @SuppressWarnings("unchecked")
    private static ChatMessageDocument msg(String thinkProcessId, ChatRole role) {
        return ChatMessageDocument.builder()
                .tenantId("t")
                .sessionId("s1")
                .thinkProcessId(thinkProcessId)
                .role(role)
                .content("reply")
                .build();
    }
}
