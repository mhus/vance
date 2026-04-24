package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Closes the bound session and the WebSocket. Requires a session (default
 * {@link WsHandler#canExecute(ConnectionContext)}).
 */
@Component
@RequiredArgsConstructor
public class LogoutHandler implements WsHandler {

    private final SessionService sessionService;

    @Override
    public String type() {
        return MessageType.LOGOUT;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        if (sessionId != null) {
            sessionService.close(sessionId);
            ctx.unbindSession();
        }
        wsSession.close(CloseStatus.NORMAL);
    }
}
