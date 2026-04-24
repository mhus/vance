package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ClientToolRegisterRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
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
        registry.register(
                ctx.getSessionId(),
                ctx.getConnectionId(),
                wsSession,
                List.copyOf(request.getTools()));
        sender.sendReply(wsSession, envelope, MessageType.CLIENT_TOOL_REGISTER, null);
    }
}
