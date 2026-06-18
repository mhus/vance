package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentSubscribeRequest;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-frame demux for the {@code documents} channel of the Live-WS
 * multi-channel envelope. Parses the inner {@link WebSocketEnvelope}'s
 * {@code type}, validates the payload, and delegates to
 * {@link DocumentSubscriberRegistry}.
 *
 * <p>Bounded subscription count per WebSocket: {@value #MAX_PATHS_PER_WS}
 * (see {@code planning/document-presence.md} §10). Exceeding it returns a
 * {@code 429}-style error frame on the {@code documents} channel and
 * leaves the registry state unchanged.
 *
 * <p>No ACL check on subscribe — see {@code planning/document-presence.md}
 * §5.3 for the rationale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentChannelHandler {

    private static final int MAX_PATHS_PER_WS = 100;

    private final DocumentSubscriberRegistry registry;
    private final WebSocketSender sender;
    private final ObjectMapper objectMapper;

    public void handle(
            WebSocketSession wsSession,
            ConnectionContext ctx,
            LiveEnvelope live) throws java.io.IOException {

        if (live.getPayload() == null) {
            sendError(wsSession, 400, "documents-channel frame missing 'payload'");
            return;
        }
        WebSocketEnvelope inner;
        try {
            inner = objectMapper.convertValue(live.getPayload(), WebSocketEnvelope.class);
        } catch (RuntimeException e) {
            sendError(wsSession, 400, "Invalid documents-channel payload: " + e.getMessage());
            return;
        }

        String type = inner.getType();
        if (type == null || type.isBlank()) {
            sendError(wsSession, 400, "documents-channel payload missing 'type'");
            return;
        }
        switch (type) {
            case MessageType.DOCUMENT_SUBSCRIBE -> handleSubscribe(wsSession, ctx, inner);
            case MessageType.DOCUMENT_UNSUBSCRIBE -> handleUnsubscribe(wsSession, inner);
            case MessageType.DOCUMENT_UNSUBSCRIBE_ALL -> registry.unsubscribeAll(wsSession);
            default -> sendError(wsSession, 400,
                    "Unknown documents-channel type: '" + type + "'");
        }
    }

    private void handleSubscribe(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws java.io.IOException {
        DocumentSubscribeRequest req = parseSubscribeRequest(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "documents.subscribe payload missing 'path'");
            return;
        }
        // Per-WS path-count cap — protects against runaway clients without
        // adding a hard global limit.
        Set<String> subscribedPaths = subscribedPathsOf(wsSession);
        if (!subscribedPaths.contains(req.getPath()) && subscribedPaths.size() >= MAX_PATHS_PER_WS) {
            sendError(wsSession, 429,
                    "Subscription limit reached (" + MAX_PATHS_PER_WS + " paths per connection)");
            return;
        }
        registry.subscribe(wsSession, ctx, req.getPath());
    }

    private void handleUnsubscribe(WebSocketSession wsSession, WebSocketEnvelope inner)
            throws java.io.IOException {
        DocumentSubscribeRequest req = parseSubscribeRequest(inner);
        if (req == null || isBlank(req.getPath())) {
            sendError(wsSession, 400, "documents.unsubscribe payload missing 'path'");
            return;
        }
        registry.unsubscribe(wsSession, req.getPath());
    }

    private DocumentSubscribeRequest parseSubscribeRequest(WebSocketEnvelope inner) {
        Object data = inner.getData();
        if (data == null) return null;
        try {
            return objectMapper.convertValue(data, DocumentSubscribeRequest.class);
        } catch (RuntimeException e) {
            log.debug("documents-channel: cannot decode subscribe payload: {}", e.toString());
            return null;
        }
    }

    private Set<String> subscribedPathsOf(WebSocketSession wsSession) {
        return registry.pathsOf(wsSession);
    }

    private void sendError(WebSocketSession wsSession, int code, String message) throws java.io.IOException {
        de.mhus.vance.api.ws.ErrorData err = de.mhus.vance.api.ws.ErrorData.builder()
                .errorCode(code)
                .errorMessage(message)
                .build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.ERROR, err);
        sender.sendOnChannel(wsSession, "documents", envelope);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
