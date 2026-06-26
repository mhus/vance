package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ClientToolRegisterRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound handler for {@link MessageType#CLIENT_TOOL_REGISTER}. Stores
 * the client's tool list against the bound session so the
 * {@link ClientToolSource} can surface them. Re-registration replaces
 * the previous list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientToolRegisterHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ClientToolRegistry registry;
    private final SessionService sessionService;

    @Override
    public String type() {
        return MessageType.CLIENT_TOOL_REGISTER;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ClientToolRegisterRequest request;
        try {
            request = objectMapper.convertValue(
                    envelope.getData(), ClientToolRegisterRequest.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid client-tool-register payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getTools() == null) {
            sender.sendError(wsSession, envelope, 400, "tools list is required");
            return;
        }
        // Owner-only registration — see planning/multi-user-sessions.md §2.5.
        // The agent must always route client-tool invocations to the
        // session-owner's WebSocket. A secondary participant in a shared
        // session that hands in its own tool list would otherwise either
        // overwrite the owner's surface or route invocations to a foreign
        // client. Silently accept (success reply) without persisting so
        // the secondary's foot/cortex doesn't crash on an unexpected error.
        if (ctx.getSessionId() == null) {
            sender.sendError(wsSession, envelope, 409, "No session bound");
            return;
        }
        SessionDocument session = sessionService.findBySessionId(ctx.getSessionId()).orElse(null);
        boolean ownerCall = session != null && session.getUserId().equals(ctx.getUserId());
        if (!ownerCall) {
            log.debug("ClientToolRegistry: ignoring non-owner registration on session='{}' "
                            + "user='{}' editor='{}' (owner='{}')",
                    ctx.getSessionId(), ctx.getUserId(), ctx.getEditorId(),
                    session == null ? "?" : session.getUserId());
            sender.sendReply(wsSession, envelope, MessageType.CLIENT_TOOL_REGISTER, null);
            return;
        }
        registry.register(
                ctx.getSessionId(),
                ctx.getEditorId(),
                wsSession,
                List.copyOf(request.getTools()));
        sender.sendReply(wsSession, envelope, MessageType.CLIENT_TOOL_REGISTER, null);
    }
}
