package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thin seam for sending {@code client-tool-invoke} envelopes. Split out
 * of {@link ClientToolSource} so tests can substitute the outbound
 * channel without mocking Spring's WebSocket plumbing.
 */
@Component
@RequiredArgsConstructor
class ClientToolChannel {

    private final WebSocketSender sender;

    void sendInvoke(
            WebSocketSession wsSession,
            String correlationId,
            String toolName,
            Map<String, Object> params) throws IOException {
        ClientToolInvokeRequest req = ClientToolInvokeRequest.builder()
                .correlationId(correlationId)
                .name(toolName)
                .params(params)
                .build();
        sender.sendNotification(wsSession, MessageType.CLIENT_TOOL_INVOKE, req);
    }
}
