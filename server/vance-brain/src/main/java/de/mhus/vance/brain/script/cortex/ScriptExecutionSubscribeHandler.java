package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.api.scripts.ScriptExecutionSubscribeRequest;
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
 * Binds the calling WebSocket to a Script-Cortex execution id so the
 * client receives the {@code script-execution-*} push frames. Idempotent
 * — re-subscribing the same id overwrites the binding.
 *
 * <p>Replies with the same {@code SCRIPT_EXECUTION_SUBSCRIBE} type, no
 * payload, on success. Errors return via {@link WebSocketSender#sendError}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScriptExecutionSubscribeHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ScriptExecutionWsRegistry registry;

    @Override
    public String type() {
        return MessageType.SCRIPT_EXECUTION_SUBSCRIBE;
    }

    /**
     * Subscriptions are addressed by {@code executionId}, not by Vance
     * session — the Script Cortex UI is reachable from the index page
     * without ever creating a chat session. Override the default
     * "session required" gate so the subscribe works regardless.
     */
    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ScriptExecutionSubscribeRequest req;
        try {
            req = objectMapper.convertValue(envelope.getData(), ScriptExecutionSubscribeRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid script-execution-subscribe payload: " + e.getMessage());
            return;
        }
        if (req == null || req.getExecutionId() == null || req.getExecutionId().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "executionId is required");
            return;
        }
        registry.register(req.getExecutionId(), wsSession);
        sender.sendReply(wsSession, envelope, MessageType.SCRIPT_EXECUTION_SUBSCRIBE, null);
    }
}
