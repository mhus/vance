package de.mhus.vance.foot.connection;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Routes inbound {@link WebSocketEnvelope}s to the appropriate
 * {@link MessageHandler} bean. Built from all {@code MessageHandler}s found
 * in the application context at startup; duplicate {@code messageType()}
 * values fail the boot.
 */
@Component
public class MessageDispatcher {

    private final Map<String, MessageHandler> handlers;
    private final ChatTerminal terminal;
    private final Map<String, CompletableFuture<WebSocketEnvelope>> pendingReplies = new ConcurrentHashMap<>();

    public MessageDispatcher(List<MessageHandler> handlerBeans, ChatTerminal terminal) {
        this.terminal = terminal;
        Map<String, MessageHandler> registry = new HashMap<>();
        for (MessageHandler handler : handlerBeans) {
            String type = handler.messageType();
            MessageHandler previous = registry.put(type, handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate MessageHandler for type '" + type + "': "
                                + previous.getClass().getName() + " and "
                                + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(registry);
    }

    public void dispatch(WebSocketEnvelope envelope) {
        String replyTo = envelope.getReplyTo();
        if (replyTo != null) {
            CompletableFuture<WebSocketEnvelope> waiting = pendingReplies.remove(replyTo);
            if (waiting != null) {
                waiting.complete(envelope);
                return;
            }
            terminal.println(Verbosity.DEBUG,
                    "Reply for unknown request id '%s' (type=%s) — request expired or duplicate.",
                    replyTo, envelope.getType());
            return;
        }
        MessageHandler handler = handlers.get(envelope.getType());
        if (handler == null) {
            terminal.println(Verbosity.DEBUG,
                    "No handler for message type '%s' (id=%s)",
                    envelope.getType(), envelope.getId());
            return;
        }
        try {
            handler.handle(envelope);
        } catch (Exception e) {
            terminal.error("Handler for '" + envelope.getType() + "' failed: " + e.getMessage());
        }
    }

    /**
     * Registers a future that completes when an envelope with {@code replyTo == id}
     * arrives. Used by {@link ConnectionService#request} for request/reply routing.
     */
    public void registerPendingReply(String id, CompletableFuture<WebSocketEnvelope> future) {
        pendingReplies.put(id, future);
    }

    /** Cancels a pending reply registration — used when the send itself fails. */
    public void cancelPendingReply(String id) {
        pendingReplies.remove(id);
    }

    /** Fails all pending replies. Called by {@link ConnectionService} on disconnect. */
    public void failAllPending(Throwable cause) {
        pendingReplies.values().forEach(f -> f.completeExceptionally(cause));
        pendingReplies.clear();
    }
}
