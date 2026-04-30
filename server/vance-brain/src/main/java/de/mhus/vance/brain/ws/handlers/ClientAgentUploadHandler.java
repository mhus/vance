package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.ClientAgentUploadRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound handler for {@link MessageType#CLIENT_AGENT_UPLOAD}. Stores
 * the client-supplied agent doc body on the bound session so the
 * memory-context loader can splice it into prompts when the active
 * recipe's profile-block opts in via {@code params.useClientAgentDoc=true}.
 *
 * <p>Re-uploads replace the previous content. Empty content clears it.
 * Soft size cap of 64 KB — oversize uploads are rejected with HTTP-style 413.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientAgentUploadHandler implements WsHandler {

    /** Soft size cap on the uploaded doc body. */
    public static final int MAX_DOC_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;

    @Override
    public String type() {
        return MessageType.CLIENT_AGENT_UPLOAD;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return ctx.hasSession();
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ClientAgentUploadRequest request;
        try {
            request = objectMapper.convertValue(
                    envelope.getData(), ClientAgentUploadRequest.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid client-agent-upload payload: " + e.getMessage());
            return;
        }
        if (request == null) {
            sender.sendError(wsSession, envelope, 400, "empty client-agent-upload payload");
            return;
        }
        String content = request.getContent();
        if (content == null) {
            sender.sendError(wsSession, envelope, 400, "content is required (use empty string to clear)");
            return;
        }
        if (content.length() > MAX_DOC_BYTES) {
            sender.sendError(wsSession, envelope, 413,
                    "agent doc exceeds size limit of " + MAX_DOC_BYTES + " chars");
            return;
        }
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        // Empty content clears the stored doc — same wire shape, different intent.
        sessionService.setClientAgentDoc(
                sessionId, request.getPath(), content.isEmpty() ? null : content);
        log.debug("client-agent-upload: tenant='{}' session='{}' path='{}' chars={}",
                ctx.getTenantId(), sessionId, request.getPath(), content.length());
        sender.sendReply(wsSession, envelope, MessageType.CLIENT_AGENT_UPLOAD, null);
    }
}
