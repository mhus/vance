package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The A6 flow-back: the conversation of an own session reaches the loop that
 * is alive now — and nothing else does (spoofed names, watch sessions, the
 * Trillian's own echo, worker chatter).
 */
class TrillianSessionReplyListenerTest {

    private static final String ACCOUNT = "_trillian-void-1234";

    private final SessionService sessionService = mock(SessionService.class);
    private final TrillianOwnSessions ownSessions = mock(TrillianOwnSessions.class);
    private final EngineMessageRouter router = mock(EngineMessageRouter.class);
    private final TrillianSessionReplyListener listener =
            new TrillianSessionReplyListener(sessionService, ownSessions, router);

    @Test
    void anEngineAnswer_reachesTheLiveLoop() {
        givenSession(ACCOUNT, CollabMode.JOIN);
        givenLiveLoop("loop-new");

        listener.onChatMessageAppended(event(ChatRole.ASSISTANT, null, "chat-1"));

        verify(router).dispatch(eq(null), eq("loop-new"), any());
    }

    @Test
    void aSessionNamedAfterATrillianByMallory_isIgnored() {
        givenSession("mallory", CollabMode.JOIN);
        givenLiveLoop("loop-new");

        listener.onChatMessageAppended(event(ChatRole.ASSISTANT, null, "chat-1"));

        verify(router, never()).dispatch(any(), anyString(), any());
    }

    @Test
    void aWatchSession_doesNotWakeTheLoop() {
        givenSession(ACCOUNT, CollabMode.WATCH);
        givenLiveLoop("loop-new");

        listener.onChatMessageAppended(event(ChatRole.ASSISTANT, null, "chat-1"));

        verify(router, never()).dispatch(any(), anyString(), any());
    }

    @Test
    void forwardableSender_dropsEchoWorkerLinesAndUnclaimedLines() {
        SessionDocument session = session(ACCOUNT, CollabMode.JOIN);

        assertThat(TrillianSessionReplyListener.forwardableSender(
                        msg(ChatRole.ASSISTANT, null, "chat-1"), session, ACCOUNT))
                .isEqualTo("engine");
        assertThat(TrillianSessionReplyListener.forwardableSender(
                        msg(ChatRole.USER, "mara", "chat-1"), session, ACCOUNT))
                .isEqualTo("mara");
        // The Trillian's own send, echoed back by the append.
        assertThat(TrillianSessionReplyListener.forwardableSender(
                        msg(ChatRole.USER, ACCOUNT, "chat-1"), session, ACCOUNT))
                .isNull();
        // A line nobody claims cannot be told from the Trillian's own input.
        assertThat(TrillianSessionReplyListener.forwardableSender(msg(ChatRole.USER, null, "chat-1"), session, ACCOUNT))
                .isNull();
        // A worker process inside that session.
        assertThat(TrillianSessionReplyListener.forwardableSender(
                        msg(ChatRole.ASSISTANT, null, "worker-7"), session, ACCOUNT))
                .isNull();
    }

    @Test
    void interimNotes_doNotWakeTheLoop() {
        givenSession(ACCOUNT, CollabMode.JOIN);
        givenLiveLoop("loop-new");
        ChatMessageDocument interim = msg(ChatRole.ASSISTANT, null, "chat-1");
        interim.getMeta().put(ChatMessageDocument.META_KIND, ChatMessageDocument.KIND_INTERIM);

        listener.onChatMessageAppended(new ChatMessageAppendedEvent(interim));

        verify(router, never()).dispatch(any(), anyString(), any());
    }

    private void givenSession(String owner, CollabMode mode) {
        when(sessionService.findBySessionId("s-1")).thenReturn(Optional.of(session(owner, mode)));
    }

    private void givenLiveLoop(String id) {
        ThinkProcessDocument loop = ThinkProcessDocument.builder()
                .id(id)
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(ownSessions.liveLoopOf("acme", ACCOUNT)).thenReturn(Optional.of(loop));
    }

    private static SessionDocument session(String owner, CollabMode mode) {
        SessionDocument s = new SessionDocument();
        s.setSessionId("s-1");
        s.setTenantId("acme");
        s.setUserId(owner);
        s.setChatProcessId("chat-1");
        s.setClientName(TrillianOwnSessions.clientName(mode, ACCOUNT));
        return s;
    }

    private static ChatMessageAppendedEvent event(ChatRole role, String sender, String processId) {
        return new ChatMessageAppendedEvent(msg(role, sender, processId));
    }

    private static ChatMessageDocument msg(ChatRole role, String sender, String processId) {
        return ChatMessageDocument.builder()
                .id("m-1")
                .tenantId("acme")
                .sessionId("s-1")
                .thinkProcessId(processId)
                .role(role)
                .senderUserId(sender)
                .content("the report is ready")
                .build();
    }
}
