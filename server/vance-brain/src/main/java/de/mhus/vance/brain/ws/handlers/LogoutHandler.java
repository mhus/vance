package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Closes the bound session via the close-cascade
 * ({@link SessionLifecycleService#closeWithCascade}) — engines stop on
 * their lanes, then the session document flips to {@code CLOSED}. After
 * the cascade the WebSocket itself is closed.
 *
 * <p>Distinct from a plain disconnect: logout is the explicit "I'm done"
 * signal, so even sessions whose {@code onDisconnect} would normally
 * suspend get fully closed here.
 */
@Component
@RequiredArgsConstructor
public class LogoutHandler implements WsHandler {

    private final SessionLifecycleService sessionLifecycle;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.LOGOUT;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        if (sessionId != null) {
            authority.enforce(ctx,
                    new Resource.Session(ctx.getTenantId(),
                            ctx.getProjectId() == null ? "" : ctx.getProjectId(), sessionId),
                    Action.EXECUTE);
            sessionLifecycle.closeWithCascade(sessionId);
            ctx.unbindSession();
        }
        wsSession.close(CloseStatus.NORMAL);
    }
}
