package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessCountsNotification;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService.ProcessCounts;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

/**
 * The coalescing contract of {@link ProcessCountsPusher}: a frame per
 * <em>number change</em>, not per status transition. Without that filter the
 * per-turn {@code RUNNING ↔ IDLE} flapping of every worker would put one
 * frame per LLM turn on the wire.
 */
class ProcessCountsPusherTest {

    private static final String TENANT = "t";
    private static final String SESSION = "s-1";

    private ThinkProcessService thinkProcessService;
    private ClientEventPublisher events;
    private WebSocketSender sender;
    private ProcessCountsPusher pusher;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        events = mock(ClientEventPublisher.class);
        sender = mock(WebSocketSender.class);
        pusher = new ProcessCountsPusher(thinkProcessService, events, sender);
        when(events.publish(any(), any(), any())).thenReturn(true);
    }

    @Test
    void statusChange_publishesCountsOnce() {
        givenCounts(new ProcessCounts(2, 1, 0));

        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));

        ProcessCountsNotification sent = capturePublished();
        assertThat(sent.getRunning()).isEqualTo(2);
        assertThat(sent.getWaiting()).isEqualTo(1);
        assertThat(sent.getBlocked()).isZero();
        assertThat(sent.getTotal()).isEqualTo(3);
        assertThat(sent.getSessionId()).isEqualTo(SESSION);
    }

    @Test
    void unchangedCounts_areCoalescedAway() {
        givenCounts(new ProcessCounts(1, 0, 0));

        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));
        pusher.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.IDLE));
        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));

        verify(events, times(1)).publish(eq(SESSION), eq(MessageType.PROCESS_COUNTS), any());
    }

    @Test
    void changedCounts_publishAgain() {
        when(thinkProcessService.countBySession(
                TENANT, SESSION, SessionChatBootstrapper.CHAT_PROCESS_NAME))
                .thenReturn(new ProcessCounts(1, 0, 0), new ProcessCounts(1, 0, 1));

        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));
        pusher.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.BLOCKED));

        verify(events, times(2)).publish(eq(SESSION), eq(MessageType.PROCESS_COUNTS), any());
    }

    @Test
    void noOpTransition_doesNotEvenCount() {
        pusher.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.RUNNING));

        verify(thinkProcessService, never()).countBySession(any(), any(), any());
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void undeliveredPublish_forgetsBaselineSoReconnectGetsAFreshFrame() {
        givenCounts(new ProcessCounts(1, 0, 0));
        when(events.publish(any(), any(), any())).thenReturn(false);

        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));
        pusher.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.IDLE));

        // Same numbers both times: with a remembered baseline the second
        // attempt would have been coalesced away. It must not be, because no
        // client ever saw the first one.
        verify(events, times(2)).publish(eq(SESSION), eq(MessageType.PROCESS_COUNTS), any());
    }

    @Test
    void pushInitial_sendsToTheBoundConnectionAndSetsTheBaseline() throws Exception {
        givenCounts(new ProcessCounts(0, 2, 0));
        WebSocketSession ws = mock(WebSocketSession.class);

        pusher.pushInitial(ws, TENANT, SESSION);
        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));

        verify(sender).sendNotification(eq(ws), eq(MessageType.PROCESS_COUNTS), any());
        // Baseline from pushInitial → the equal delta is coalesced away.
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void countingExcludesTheSessionChatProcess() {
        givenCounts(new ProcessCounts(0, 0, 0));

        pusher.onStatusChanged(event(ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));

        verify(thinkProcessService).countBySession(
                TENANT, SESSION, SessionChatBootstrapper.CHAT_PROCESS_NAME);
    }

    @Test
    void blankSessionId_isIgnored() {
        pusher.onStatusChanged(new ThinkProcessStatusChangedEvent(
                "p-1", TENANT, "", null,
                ThinkProcessStatus.IDLE, ThinkProcessStatus.RUNNING));

        verify(thinkProcessService, never()).countBySession(any(), any(), any());
    }

    private void givenCounts(ProcessCounts counts) {
        when(thinkProcessService.countBySession(
                TENANT, SESSION, SessionChatBootstrapper.CHAT_PROCESS_NAME))
                .thenReturn(counts);
    }

    private ProcessCountsNotification capturePublished() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(SESSION), eq(MessageType.PROCESS_COUNTS), payload.capture());
        return (ProcessCountsNotification) payload.getValue();
    }

    private static ThinkProcessStatusChangedEvent event(
            ThinkProcessStatus prior, ThinkProcessStatus next) {
        return new ThinkProcessStatusChangedEvent("p-1", TENANT, SESSION, null, prior, next);
    }
}
