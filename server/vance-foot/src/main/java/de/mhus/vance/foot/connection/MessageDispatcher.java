package de.mhus.vance.foot.connection;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        MessageHandler handler = handlers.get(envelope.getType());
        if (handler == null) {
            terminal.println(Verbosity.DEBUG,
                    "No handler for message type '%s' (id=%s, replyTo=%s)",
                    envelope.getType(), envelope.getId(), envelope.getReplyTo());
            return;
        }
        try {
            handler.handle(envelope);
        } catch (Exception e) {
            terminal.error("Handler for '" + envelope.getType() + "' failed: " + e.getMessage());
        }
    }
}
