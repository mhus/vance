package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound handler for {@link MessageType#CLIENT_TOOL_RESULT}. Matches
 * the incoming correlation id to a pending invocation and completes
 * its future with the result or error.
 *
 * <p>Unknown correlation ids are logged but not rejected — the invoke
 * likely timed out and was already cancelled; surfacing that back to
 * the client would be noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientToolResultHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ClientToolRegistry registry;

    @Override
    public String type() {
        return MessageType.CLIENT_TOOL_RESULT;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ClientToolInvokeResponse response;
        try {
            response = objectMapper.convertValue(
                    envelope.getData(), ClientToolInvokeResponse.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid client-tool-result payload: " + e.getMessage());
            return;
        }
        if (response == null || response.getCorrelationId() == null) {
            sender.sendError(wsSession, envelope, 400,
                    "correlationId is required");
            return;
        }
        boolean matched = registry.completeInvocation(
                response.getCorrelationId(),
                response.getResult(),
                response.getError()).isPresent();
        if (!matched) {
            log.debug("Dropping late client-tool-result correlationId='{}'",
                    response.getCorrelationId());
        }
    }
}
