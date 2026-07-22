package de.mhus.vance.brain.ws.signals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.SignalFrame;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class SignalBroadcasterTest {

    private WebSocketSender sender;
    private SignalBroadcaster broadcaster;

    @BeforeEach
    void setup() {
        sender = mock(WebSocketSender.class);
        VanceRedisMessagingService redis = mock(VanceRedisMessagingService.class);
        broadcaster = new SignalBroadcaster(sender, redis, new ObjectMapper());
    }

    private WebSocketSession sub(String wsId, String tenantId, String path) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(wsId);
        ConnectionContext ctx = new ConnectionContext(
                tenantId, "user-" + wsId, null, "default", "1.0", "web", "ed-" + wsId, "10.0.0.1");
        broadcaster.subscribe(ws, ctx, path);
        return ws;
    }

    private SignalFrame frame(String path) {
        return SignalFrame.builder().path(path).signal("compose-run")
                .data(Map.of("runId", "cr-1", "status", "running")).build();
    }

    @Test
    void broadcast_reachesSubscriberOfPath() throws Exception {
        WebSocketSession ws = sub("w1", "acme", "notes/x.compose.yaml");

        broadcaster.broadcast("acme", frame("notes/x.compose.yaml"));

        verify(sender).sendOnChannel(eq(ws), eq("signals"), any());
    }

    @Test
    void broadcast_noSubscribers_isNoOp() throws Exception {
        broadcaster.broadcast("acme", frame("notes/unwatched.compose.yaml"));

        verify(sender, never()).sendOnChannel(any(), any(), any());
    }

    @Test
    void broadcast_isTenantScoped_notLeakedToOtherTenantSamePath() throws Exception {
        String path = "notes/x.compose.yaml";
        WebSocketSession acme = sub("w1", "acme", path);
        WebSocketSession other = sub("w2", "other", path);

        broadcaster.broadcast("acme", frame(path));

        verify(sender).sendOnChannel(eq(acme), eq("signals"), any());
        verify(sender, never()).sendOnChannel(eq(other), any(), any());
    }

    @Test
    void unsubscribe_stopsDelivery() throws Exception {
        String path = "notes/x.compose.yaml";
        WebSocketSession ws = sub("w1", "acme", path);
        broadcaster.unsubscribe(ws, path);

        broadcaster.broadcast("acme", frame(path));

        verify(sender, never()).sendOnChannel(any(), any(), any());
    }
}
