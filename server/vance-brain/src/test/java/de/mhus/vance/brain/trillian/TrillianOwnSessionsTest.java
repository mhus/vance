package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What makes a session "the Trillian's own" (A6 / D6): the marker in the
 * client name is a claim anybody can make at the WS handshake, the owner is
 * the proof — and the addressing of {@code session_send}.
 */
class TrillianOwnSessionsTest {

    private static final String ACCOUNT = "_trillian-void-1234";

    private final SessionService sessionService = mock(SessionService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final EngineMessageRouter messageRouter = mock(EngineMessageRouter.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);

    private final TrillianOwnSessions own = new TrillianOwnSessions(
            sessionService, thinkProcessService, permissionService, contextFactory, messageRouter, chatMessageService);

    private final ToolInvocationContext ctx =
            new ToolInvocationContext("acme", "_user_" + ACCOUNT, "s-loop", "loop-1", ACCOUNT);

    @Test
    void addressed_prependsTheMentionWhenMissing() {
        assertThat(TrillianOwnSessions.addressed("please look at the report"))
                .isEqualTo("@ai please look at the report");
    }

    @Test
    void addressed_keepsAMentionTheCallerWrote() {
        assertThat(TrillianOwnSessions.addressed("@human I disagree")).isEqualTo("@human I disagree");
        assertThat(TrillianOwnSessions.addressed("   @ai already there")).isEqualTo("   @ai already there");
    }

    @Test
    void clientName_roundTripsModeAndAccount() {
        String name = TrillianOwnSessions.clientName(CollabMode.WATCH, ACCOUNT);

        assertThat(TrillianOwnSessions.parse(name)).contains(new TrillianOwnSessions.Marker(CollabMode.WATCH, ACCOUNT));
    }

    @Test
    void parse_rejectsAnythingThatIsNotAMarker() {
        assertThat(TrillianOwnSessions.parse("foot")).isEmpty();
        assertThat(TrillianOwnSessions.parse("trillian-session:64f0c0ffee")).isEmpty();
        assertThat(TrillianOwnSessions.parse("trillian-session:loud:" + ACCOUNT))
                .isEmpty();
        assertThat(TrillianOwnSessions.parse(null)).isEmpty();
    }

    @Test
    void verifiedMarker_ignoresASessionNamedAfterSomeoneElse() {
        // A human may call their session anything at the handshake; naming it
        // after a Trillian must not route its chat into that Trillian.
        SessionDocument spoof = session("s-1", "mallory", TrillianOwnSessions.clientName(CollabMode.JOIN, ACCOUNT));

        assertThat(TrillianOwnSessions.verifiedMarker(spoof)).isEmpty();
    }

    @Test
    void requireOwn_refusesTheTrilliansOwnLoopSession() {
        // Same owner, but opened by the bootstrap rather than session_open —
        // session_close on it would end the Trillian's own loop.
        when(sessionService.findBySessionId("s-loop"))
                .thenReturn(Optional.of(session("s-loop", ACCOUNT, "trillian-bootstrap")));

        assertThatThrownBy(() -> own.requireOwn("s-loop", ctx, Action.EXECUTE))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("session_open");
    }

    @Test
    void requireOwn_rechecksTheRightOnEveryCall() {
        when(sessionService.findBySessionId("s-1"))
                .thenReturn(
                        Optional.of(session("s-1", ACCOUNT, TrillianOwnSessions.clientName(CollabMode.JOIN, ACCOUNT))));

        own.requireOwn("s-1", ctx, Action.EXECUTE);

        verify(permissionService).enforce(any(), any(), eq(Action.EXECUTE));
    }

    @Test
    void deliver_routesAnAgentMessageThroughTheRouter() {
        when(messageRouter.dispatch(any(), any(), any())).thenReturn(true);
        SessionDocument session = session("s-1", ACCOUNT, TrillianOwnSessions.clientName(CollabMode.JOIN, ACCOUNT));

        boolean toEngine = own.deliver(session, chat(), ACCOUNT, "loop-1", "summarise the backlog");

        assertThat(toEngine).isTrue();
        verify(messageRouter).dispatch(eq("loop-1"), eq("chat-1"), any());
        verify(chatMessageService, never()).append(any());
    }

    @Test
    void deliver_onlyRecordsAMessageForAPerson() {
        // "@mara …" is the Trillian talking to a human in the shared session;
        // the project's engine must not run on it.
        SessionDocument session = session("s-1", ACCOUNT, TrillianOwnSessions.clientName(CollabMode.JOIN, ACCOUNT));

        boolean toEngine = own.deliver(session, chat(), ACCOUNT, "loop-1", "@mara can you confirm?");

        assertThat(toEngine).isFalse();
        verify(messageRouter, never()).dispatch(any(), any(), any());
        ArgumentCaptor<ChatMessageDocument> line = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(line.capture());
        assertThat(line.getValue().getSenderUserId()).isEqualTo(ACCOUNT);
    }

    @Test
    void liveLoopOf_skipsClosedGenerations() {
        ThinkProcessDocument closed = ThinkProcessDocument.builder()
                .id("old")
                .status(ThinkProcessStatus.CLOSED)
                .build();
        ThinkProcessDocument live = ThinkProcessDocument.builder()
                .id("new")
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(thinkProcessService.findByProjectAndEngines(
                        eq("acme"), eq("_user_" + ACCOUNT), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(closed, live));

        assertThat(own.liveLoopOf("acme", ACCOUNT))
                .map(ThinkProcessDocument::getId)
                .contains("new");
    }

    private static SessionDocument session(String id, String owner, String clientName) {
        SessionDocument s = new SessionDocument();
        s.setSessionId(id);
        s.setTenantId("acme");
        s.setProjectId("p1");
        s.setUserId(owner);
        s.setClientName(clientName);
        s.setChatProcessId("chat-1");
        return s;
    }

    private static ThinkProcessDocument chat() {
        return ThinkProcessDocument.builder()
                .id("chat-1")
                .status(ThinkProcessStatus.IDLE)
                .build();
    }
}
