package de.mhus.vance.brain.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * USER-message dispatch in {@link ChatMessageNotificationDispatcher}
 * is mode-dependent (see {@code planning/multi-user-sessions.md} §3.5):
 *
 * <ul>
 *   <li>Solo session (1 connection): skip — the sender already
 *       rendered optimistically.</li>
 *   <li>Multi-user session (&gt;=2 connections): broadcast to every
 *       connection; the sender's client filters its own echo by
 *       comparing senderUserId.</li>
 * </ul>
 *
 * <p>ASSISTANT/SYSTEM messages always broadcast.
 */
class ChatMessageNotificationDispatcherTest {

    private SessionConnectionRegistry registry;
    private ThinkProcessService thinkProcessService;
    private WebSocketSender sender;
    private ChatMessageNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new SessionConnectionRegistry();
        thinkProcessService = mock(ThinkProcessService.class);
        sender = mock(WebSocketSender.class);
        when(thinkProcessService.findById(any())).thenReturn(Optional.empty());
        dispatcher = new ChatMessageNotificationDispatcher(
                registry, thinkProcessService, sender);
    }

    @Test
    void userMessage_inSoloSession_isSkipped() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", ws, false);

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(
                userMessage("s1", "alice")));

        verify(sender, never()).sendNotification(any(), any(), any());
    }

    @Test
    void userMessage_inMultiUserSession_broadcastsToAll() throws Exception {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, true);
        registry.register("s1", "bob", "ed-2", bobWs, true);

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(
                userMessage("s1", "alice")));

        verify(sender).sendNotification(eq(aliceWs),
                eq(MessageType.CHAT_MESSAGE_APPENDED), any());
        verify(sender).sendNotification(eq(bobWs),
                eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @Test
    void assistantMessage_inSoloSession_isBroadcast() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", ws, false);

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(
                assistantMessage("s1")));

        verify(sender).sendNotification(eq(ws),
                eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @Test
    void assistantMessage_inMultiUserSession_broadcastsToAll() throws Exception {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, true);
        registry.register("s1", "bob", "ed-2", bobWs, true);

        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(
                assistantMessage("s1")));

        verify(sender, times(2)).sendNotification(any(),
                eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @Test
    void noConnections_isNoop() throws Exception {
        dispatcher.onChatMessageAppended(new ChatMessageAppendedEvent(
                assistantMessage("s1")));

        verify(sender, never()).sendNotification(any(), any(), any());
    }

    private static ChatMessageDocument userMessage(String sessionId, String senderUserId) {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setId("m-" + System.nanoTime());
        doc.setSessionId(sessionId);
        doc.setThinkProcessId("p-1");
        doc.setRole(ChatRole.USER);
        doc.setContent("hello");
        doc.setSenderUserId(senderUserId);
        doc.setSenderDisplayName(senderUserId);
        doc.setAddressedToAgent(true);
        return doc;
    }

    private static ChatMessageDocument assistantMessage(String sessionId) {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setId("m-" + System.nanoTime());
        doc.setSessionId(sessionId);
        doc.setThinkProcessId("p-1");
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("hi back");
        doc.setAddressedToAgent(true);
        return doc;
    }
}
