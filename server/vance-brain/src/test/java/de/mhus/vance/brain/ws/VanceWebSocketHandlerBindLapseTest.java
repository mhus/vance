package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * A missed heartbeat has two very different causes and only one of them
 * warrants dropping the client: somebody else owns the session now, versus
 * the bind lease merely lapsed while nobody claimed it.
 *
 * <p>The lapse case used to close the connection too, which cost the client
 * everything tied to it (registered client-tools, in-flight tool dispatches,
 * busy/progress tracking) while the engine kept running on the brain — the
 * frame that triggered the check was itself proof the client was alive.
 */
class VanceWebSocketHandlerBindLapseTest {

    private SessionService sessionService;
    private WebSocketSender sender;
    private ObjectMapper objectMapper;
    private WebSocketSession wsSession;
    private ConnectionContext ctx;
    private AtomicInteger handled;
    private VanceWebSocketHandler dispatcher;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sender = mock(WebSocketSender.class);
        objectMapper = new ObjectMapper();
        handled = new AtomicInteger();

        SessionDocument session = new SessionDocument();
        session.setSessionId("s-1");
        session.setTenantId("acme");
        session.setProjectId("proj");

        ctx = new ConnectionContext(
                "acme", "alice", null, "default", "1.0", "vance-foot",
                "editor-1", "10.0.0.1");
        ctx.bindSession(session);

        wsSession = mock(WebSocketSession.class);
        when(wsSession.getAttributes()).thenReturn(java.util.Map.of(
                VanceHandshakeInterceptor.ATTR_CONNECTION, ctx));

        WsHandler counting = new WsHandler() {
            @Override public String type() { return "counted.message"; }
            @Override public boolean canExecute(ConnectionContext c) { return true; }
            @Override
            public void handle(ConnectionContext c, WebSocketSession s, WebSocketEnvelope e) {
                handled.incrementAndGet();
            }
        };
        dispatcher = new VanceWebSocketHandler(
                sessionService, mock(SessionLifecycleService.class),
                new VanceBrainProperties(), objectMapper, sender,
                mock(ClientToolRegistry.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(SessionConnectionRegistry.class), new ExecutionRegistryService(),
                mock(de.mhus.vance.brain.script.cortex.ScriptExecutionWsRegistry.class),
                new de.mhus.vance.brain.daemon.DaemonRegistry(),
                emptyServerToolRegistryProvider(),
                new DirectWsInboundExecutor(),
                List.of(counting));
    }

    @Test
    void lapsedLease_reclaimedInsteadOfClosingTheLiveConnection() throws Exception {
        when(sessionService.heartbeat("s-1", "editor-1")).thenReturn(false);
        when(sessionService.tryBind("s-1", "editor-1")).thenReturn(true);

        dispatcher.handleTextMessage(wsSession, envelopeOf("counted.message"));

        verify(wsSession, never()).close(any(CloseStatus.class));
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    void sessionOwnedElsewhere_stillClosesTheConnection() throws Exception {
        when(sessionService.heartbeat("s-1", "editor-1")).thenReturn(false);
        when(sessionService.tryBind("s-1", "editor-1")).thenReturn(false);

        dispatcher.handleTextMessage(wsSession, envelopeOf("counted.message"));

        verify(wsSession).close(any(CloseStatus.class));
        assertThat(handled.get()).isZero();
    }

    @Test
    void freshHeartbeat_doesNotTouchTheBind() throws Exception {
        when(sessionService.heartbeat("s-1", "editor-1")).thenReturn(true);

        dispatcher.handleTextMessage(wsSession, envelopeOf("counted.message"));

        verify(sessionService, never()).tryBind(any(), any());
        assertThat(handled.get()).isEqualTo(1);
    }

    private TextMessage envelopeOf(String type) throws IOException {
        WebSocketEnvelope env = WebSocketEnvelope.request("req-1", type, null);
        return new TextMessage(objectMapper.writeValueAsString(env));
    }

    private static org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.brain.servertool.ServerToolRegistry>
            emptyServerToolRegistryProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public de.mhus.vance.brain.servertool.ServerToolRegistry getObject() {
                throw new UnsupportedOperationException();
            }
            @Override public de.mhus.vance.brain.servertool.ServerToolRegistry getObject(Object... args) {
                throw new UnsupportedOperationException();
            }
            @Override public de.mhus.vance.brain.servertool.ServerToolRegistry getIfAvailable() {
                return null;
            }
            @Override public de.mhus.vance.brain.servertool.ServerToolRegistry getIfUnique() {
                return null;
            }
        };
    }
}
