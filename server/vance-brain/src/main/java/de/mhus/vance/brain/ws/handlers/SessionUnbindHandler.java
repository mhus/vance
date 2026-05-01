package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Releases the binding between this connection and its session without
 * closing either. After this, the connection is back to its pre-session
 * state and {@code session-create} / {@code session-resume} /
 * {@code session-bootstrap} are allowed again — the unbound session
 * itself stays OPEN and is resumable later from another connection.
 *
 * <p>Semantics are intentionally symmetric with the bind points: the
 * session's binding is cleared in Mongo via {@link
 * SessionService#unbind(String, String)}, the in-memory
 * {@link ConnectionContext} is flipped, and both per-session registries
 * ({@link SessionConnectionRegistry}, {@link ClientToolRegistry}) are
 * cleared so event plumbing and client-side tools don't route to a
 * stale owner.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionUnbindHandler implements WsHandler {

    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final SessionConnectionRegistry connectionRegistry;
    private final ClientToolRegistry clientToolRegistry;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.SESSION_UNBIND;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        String connectionId = ctx.getConnectionId();
        if (sessionId == null) {
            // canExecute gates on hasSession, but be defensive.
            sender.sendError(wsSession, envelope, 409, "No session bound");
            return;
        }
        authority.enforce(ctx,
                new Resource.Session(ctx.getTenantId(),
                        ctx.getProjectId() == null ? "" : ctx.getProjectId(), sessionId),
                Action.EXECUTE);
        sessionService.unbind(sessionId, connectionId);
        clientToolRegistry.unregister(sessionId);
        connectionRegistry.unregister(sessionId);
        ctx.unbindSession();
        log.info("Session '{}' unbound from connection '{}'", sessionId, connectionId);
        sender.sendReply(wsSession, envelope, MessageType.SESSION_UNBIND, null);
    }
}
