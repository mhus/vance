package de.mhus.vance.brain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * The client event channel is optimistic rendering: a frame that cannot be
 * written must never propagate to whoever emitted the event. Progress pings
 * are emitted from inside tool invocations, so an escaping write error
 * showed up to the engine as a failed tool call.
 */
class ClientEventPublisherTest {

    private final SessionConnectionRegistry connections = mock(SessionConnectionRegistry.class);
    private final WebSocketSender sender = mock(WebSocketSender.class);
    private final ClientEventPublisher publisher = new ClientEventPublisher(connections, sender);

    @Test
    void uncheckedWriteFailure_isSwallowed() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(connections.findAll("s-1")).thenReturn(List.of(ws));
        // What Tomcat throws when the endpoint is closed or left in a
        // partial-write state — unchecked, so a catch(IOException) missed it.
        doThrow(new IllegalStateException(
                "The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(sender).sendNotification(eq(ws), any(), any());

        assertThatCode(() -> publisher.publish("s-1", "process-progress", "x"))
                .doesNotThrowAnyException();
        assertThat(publisher.publish("s-1", "process-progress", "x")).isFalse();
    }

    @Test
    void oneBrokenConnection_doesNotStopTheOthers() throws Exception {
        WebSocketSession broken = mock(WebSocketSession.class);
        WebSocketSession healthy = mock(WebSocketSession.class);
        when(connections.findAll("s-1")).thenReturn(List.of(broken, healthy));
        doThrow(new IOException("gone")).when(sender).sendNotification(eq(broken), any(), any());

        assertThat(publisher.publish("s-1", "process-progress", "x")).isTrue();
        verify(sender).sendNotification(eq(healthy), any(), any());
    }

    @Test
    void noConnections_isANoop() throws Exception {
        when(connections.findAll("s-1")).thenReturn(List.of());

        assertThat(publisher.publish("s-1", "process-progress", "x")).isFalse();
        verify(sender, org.mockito.Mockito.never()).sendNotification(any(), any(), any());
    }
}
