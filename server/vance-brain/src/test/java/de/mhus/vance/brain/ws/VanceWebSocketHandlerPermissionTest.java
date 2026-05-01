package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Verifies the dispatcher's error mapping: a handler that throws
 * {@link PermissionDeniedException} must yield a {@code 403} error frame
 * (not the generic {@code 500} that wraps unknown {@code RuntimeException}s).
 */
class VanceWebSocketHandlerPermissionTest {

    private SessionService sessionService;
    private SessionLifecycleService sessionLifecycle;
    private VanceBrainProperties properties;
    private WebSocketSender sender;
    private ClientToolRegistry clientToolRegistry;
    private SessionConnectionRegistry connectionRegistry;
    private ObjectMapper objectMapper;

    private WebSocketSession wsSession;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sessionLifecycle = mock(SessionLifecycleService.class);
        properties = new VanceBrainProperties();
        sender = mock(WebSocketSender.class);
        clientToolRegistry = mock(ClientToolRegistry.class);
        connectionRegistry = mock(SessionConnectionRegistry.class);
        objectMapper = new ObjectMapper();

        wsSession = mock(WebSocketSession.class);
        ctx = new ConnectionContext(
                "acme", "alice", null, "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
        when(wsSession.getAttributes()).thenReturn(java.util.Map.of(
                VanceHandshakeInterceptor.ATTR_CONNECTION, ctx));
    }

    @Test
    void handler_throwingPermissionDenied_mapsTo403_notGeneric500() throws Exception {
        WsHandler denyingHandler = new WsHandler() {
            @Override public String type() { return "denied.message"; }
            @Override public boolean canExecute(ConnectionContext c) { return true; }
            @Override
            public void handle(ConnectionContext c, WebSocketSession s, WebSocketEnvelope e) {
                throw new PermissionDeniedException(
                        SecurityContext.user("alice", "acme", List.of()),
                        new Resource.Tenant("acme"),
                        Action.READ);
            }
        };
        VanceWebSocketHandler dispatcher = new VanceWebSocketHandler(
                sessionService, sessionLifecycle, properties, objectMapper,
                sender, clientToolRegistry, connectionRegistry,
                List.of(denyingHandler));

        TextMessage frame = envelopeOf("denied.message");
        dispatcher.handleTextMessage(wsSession, frame);

        verify(sender).sendError(eq(wsSession), any(WebSocketEnvelope.class), eq(403),
                eq("permission_denied"));
        verify(sender, never()).sendError(eq(wsSession), any(WebSocketEnvelope.class), eq(500),
                any());
    }

    @Test
    void handler_throwingGenericRuntimeException_stillMapsTo500() throws Exception {
        WsHandler boomHandler = new WsHandler() {
            @Override public String type() { return "boom.message"; }
            @Override public boolean canExecute(ConnectionContext c) { return true; }
            @Override
            public void handle(ConnectionContext c, WebSocketSession s, WebSocketEnvelope e) {
                throw new RuntimeException("boom");
            }
        };
        VanceWebSocketHandler dispatcher = new VanceWebSocketHandler(
                sessionService, sessionLifecycle, properties, objectMapper,
                sender, clientToolRegistry, connectionRegistry,
                List.of(boomHandler));

        dispatcher.handleTextMessage(wsSession, envelopeOf("boom.message"));

        verify(sender, atLeastOnce())
                .sendError(eq(wsSession), any(WebSocketEnvelope.class), eq(500), any());
        verify(sender, never())
                .sendError(eq(wsSession), any(WebSocketEnvelope.class), eq(403), any());
    }

    private TextMessage envelopeOf(String type) throws IOException {
        WebSocketEnvelope env = WebSocketEnvelope.request("req-1", type, null);
        return new TextMessage(objectMapper.writeValueAsString(env));
    }
}
