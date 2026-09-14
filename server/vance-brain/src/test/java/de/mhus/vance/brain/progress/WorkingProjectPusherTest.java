package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WorkingProjectNotification;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.WorkingProjectChangedEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

/**
 * The broadcast contract of {@link WorkingProjectPusher}: one frame per
 * actual spot mutation — the service already swallows no-op re-sets, so
 * the pusher forwards every event it receives.
 */
class WorkingProjectPusherTest {

    private static final String TENANT = "t";
    private static final String SESSION = "s-1";

    private ThinkProcessService thinkProcessService;
    private ClientEventPublisher events;
    private WebSocketSender sender;
    private WorkingProjectPusher pusher;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        events = mock(ClientEventPublisher.class);
        sender = mock(WebSocketSender.class);
        pusher = new WorkingProjectPusher(thinkProcessService, events, sender);
    }

    @Test
    void spotChange_isBroadcastToTheSession() {
        pusher.onWorkingProjectChanged(new WorkingProjectChangedEvent("p-1", TENANT, SESSION, "security-audit"));

        WorkingProjectNotification sent = captureBroadcast();
        assertThat(sent.getSessionId()).isEqualTo(SESSION);
        assertThat(sent.getWorkingProject()).isEqualTo("security-audit");
    }

    @Test
    void clearedSpot_broadcastsNull() {
        pusher.onWorkingProjectChanged(new WorkingProjectChangedEvent("p-1", TENANT, SESSION, null));

        // Null is the state "no spot" — the client must drop its badge,
        // so the frame goes out rather than being skipped.
        WorkingProjectNotification sent = captureBroadcast();
        assertThat(sent.getWorkingProject()).isNull();
    }

    @Test
    void blankSessionId_isIgnored() {
        pusher.onWorkingProjectChanged(new WorkingProjectChangedEvent("p-1", TENANT, " ", "projA"));

        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void pushInitial_sendsTheCurrentSpotToTheBoundConnection() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(thinkProcessService.findByName(TENANT, SESSION, SessionChatBootstrapper.CHAT_PROCESS_NAME))
                .thenReturn(Optional.of(chatProcess("projA")));

        pusher.pushInitial(ws, TENANT, SESSION);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendNotification(eq(ws), eq(MessageType.WORKING_PROJECT_CHANGED), payload.capture());
        WorkingProjectNotification sent = (WorkingProjectNotification) payload.getValue();
        assertThat(sent.getSessionId()).isEqualTo(SESSION);
        assertThat(sent.getWorkingProject()).isEqualTo("projA");
    }

    @Test
    void pushInitial_withoutChatProcess_sendsNull() throws Exception {
        // Fresh session or non-Eddie engine — the null frame clears a
        // stale badge the client may carry from a previous connection.
        WebSocketSession ws = mock(WebSocketSession.class);
        when(thinkProcessService.findByName(TENANT, SESSION, SessionChatBootstrapper.CHAT_PROCESS_NAME))
                .thenReturn(Optional.empty());

        pusher.pushInitial(ws, TENANT, SESSION);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendNotification(eq(ws), eq(MessageType.WORKING_PROJECT_CHANGED), payload.capture());
        assertThat(((WorkingProjectNotification) payload.getValue()).getWorkingProject())
                .isNull();
    }

    private WorkingProjectNotification captureBroadcast() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(SESSION), eq(MessageType.WORKING_PROJECT_CHANGED), payload.capture());
        return (WorkingProjectNotification) payload.getValue();
    }

    private static ThinkProcessDocument chatProcess(String spot) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setWorkingProjectId(spot);
        return doc;
    }
}
