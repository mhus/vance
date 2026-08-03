package de.mhus.vance.brain.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Covers the server-side keep-alive eviction: a connection that stops answering
 * pings is closed (releasing its session bind), while one that pongs — or was
 * only just registered — is left alone. {@link WebSocketKeepAliveService#sweep()}
 * is driven directly so the test does not depend on the scheduler.
 */
class WebSocketKeepAliveServiceTest {

    private WebSocketKeepAliveService service(int intervalSeconds, int maxMissed) {
        VanceBrainProperties props = new VanceBrainProperties();
        props.setServerPingIntervalSeconds(intervalSeconds);
        props.setServerPingMaxMissed(maxMissed);
        return new WebSocketKeepAliveService(props);
    }

    private WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void silentSession_isEvicted() throws Exception {
        // staleMs = 1 * 1 * 1000 = 1000ms.
        WebSocketKeepAliveService keepAlive = service(1, 1);
        WebSocketSession dead = session("dead");
        keepAlive.register(dead);

        Thread.sleep(1_300); // let it cross the stale threshold without a pong

        keepAlive.sweep();

        verify(dead).close(ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    void freshlyRegisteredSession_isNotEvicted() throws Exception {
        WebSocketKeepAliveService keepAlive = service(1, 1);
        WebSocketSession fresh = session("fresh");
        keepAlive.register(fresh);

        keepAlive.sweep(); // immediate — well within the stale window

        verify(fresh, never()).close(ArgumentMatchers.any());
    }

    @Test
    void pongResetsLiveness_soSessionSurvives() throws Exception {
        WebSocketKeepAliveService keepAlive = service(1, 1);
        WebSocketSession s = session("s");
        keepAlive.register(s);

        Thread.sleep(1_300);
        keepAlive.recordPong("s"); // client answered just in time

        keepAlive.sweep();

        verify(s, never()).close(ArgumentMatchers.any());
    }
}
