package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentPrefixSubscribeRequest;
import de.mhus.vance.api.ws.DocumentSubscribeRequest;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
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
            case MessageType.DOCUMENT_SUBSCRIBE_PREFIX -> handleSubscribePrefix(wsSession, ctx, inner);
            case MessageType.DOCUMENT_UNSUBSCRIBE_PREFIX -> handleUnsubscribePrefix(wsSession, inner);
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
        // Per-WS subscription-count cap — protects against runaway
        // clients without adding a hard global limit. Shared between
        // path and prefix subs (see #handleSubscribePrefix).
        if (!registry.pathsOf(wsSession).contains(req.getPath())
                && registry.subscriptionCountOf(wsSession) >= MAX_PATHS_PER_WS) {
            sendError(wsSession, 429,
                    "Subscription limit reached (" + MAX_PATHS_PER_WS + " per connection)");
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

    private void handleSubscribePrefix(WebSocketSession wsSession, ConnectionContext ctx, WebSocketEnvelope inner)
            throws java.io.IOException {
        DocumentPrefixSubscribeRequest req = parsePrefixRequest(inner);
        if (req == null || isBlank(req.getPrefix())) {
            sendError(wsSession, 400, "documents.subscribePrefix payload missing 'prefix'");
            return;
        }
        String prefix = req.getPrefix();
        if (!prefix.endsWith("/")) {
            sendError(wsSession, 400,
                    "documents.subscribePrefix 'prefix' must end with '/'");
            return;
        }
        if (prefix.length() < 2) {
            // A single '/' would subscribe to literally every doc in the
            // tenant. That's never the intent and the resulting fan-out
            // is a footgun, not a feature.
            sendError(wsSession, 400,
                    "documents.subscribePrefix 'prefix' too short (need at least 2 chars including the trailing '/')");
            return;
        }
        if (prefix.contains("..") || prefix.contains("\\")) {
            sendError(wsSession, 400,
                    "documents.subscribePrefix 'prefix' contains forbidden sequence");
            return;
        }
        if (!registry.prefixesOf(wsSession).contains(prefix)
                && registry.subscriptionCountOf(wsSession) >= MAX_PATHS_PER_WS) {
            sendError(wsSession, 429,
                    "Subscription limit reached (" + MAX_PATHS_PER_WS + " per connection)");
            return;
        }
        registry.subscribePrefix(wsSession, ctx, prefix);
    }

    private void handleUnsubscribePrefix(WebSocketSession wsSession, WebSocketEnvelope inner)
            throws java.io.IOException {
        DocumentPrefixSubscribeRequest req = parsePrefixRequest(inner);
        if (req == null || isBlank(req.getPrefix())) {
            sendError(wsSession, 400, "documents.unsubscribePrefix payload missing 'prefix'");
            return;
        }
        registry.unsubscribePrefix(wsSession, req.getPrefix());
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

    private DocumentPrefixSubscribeRequest parsePrefixRequest(WebSocketEnvelope inner) {
        Object data = inner.getData();
        if (data == null) return null;
        try {
            return objectMapper.convertValue(data, DocumentPrefixSubscribeRequest.class);
        } catch (RuntimeException e) {
            log.debug("documents-channel: cannot decode prefix payload: {}", e.toString());
            return null;
        }
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
