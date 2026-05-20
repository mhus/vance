package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ClientToolInvokeResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.daemon.DaemonRegistry;
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
    private final DaemonRegistry daemonRegistry;

    @Override
    public String type() {
        return MessageType.CLIENT_TOOL_RESULT;
    }

    /**
     * Accept the result envelope for both session-bound clients (foot in
     * a chat session) and session-less daemons. The correlation id then
     * tells us which registry owns the pending future.
     */
    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return ctx.hasSession() || Profiles.DAEMON.equals(ctx.getProfile());
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
        // The correlation id was minted by either the session-keyed
        // ClientToolRegistry (chat-session client tools) or the
        // session-less DaemonRegistry (cross-session daemon tools). Try
        // both; the namespaces are disjoint by prefix (ct-* vs dt-*) so
        // at most one will match.
        boolean matched = registry.completeInvocation(
                response.getCorrelationId(),
                response.getResult(),
                response.getError()).isPresent();
        if (!matched) {
            matched = daemonRegistry.completeInvocation(
                    response.getCorrelationId(),
                    response.getResult(),
                    response.getError()).isPresent();
        }
        if (!matched) {
            // INFO not DEBUG: a late result is exactly the "ghost reply
            // after timeout / disconnect" signal we want surfaced.
            log.info("client-tool-result: no pending for correlation='{}' (late or unknown)",
                    response.getCorrelationId());
        } else {
            log.info("client-tool-result: matched correlation='{}'{}",
                    response.getCorrelationId(),
                    response.getError() == null ? "" : " error='" + response.getError() + "'");
        }
    }
}
