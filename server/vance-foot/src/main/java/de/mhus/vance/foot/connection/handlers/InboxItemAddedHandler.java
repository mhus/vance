package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code inbox-item-added} push notifications. Plain
 * one-liner for v1; a future TUI overlay can route on this same
 * handler.
 *
 * <p>Payload shape (per {@code WsNotificationChannel}):
 * <pre>{ itemId, criticality, title, body }</pre>
 */
@Component
public class InboxItemAddedHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public InboxItemAddedHandler(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.INBOX_ITEM_ADDED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        if (!(envelope.getData() instanceof Map<?, ?> map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) map;
        String itemId = String.valueOf(data.getOrDefault("itemId", ""));
        String criticality = String.valueOf(data.getOrDefault("criticality", "?"));
        String title = String.valueOf(data.getOrDefault("title", ""));
        String shortId = itemId.length() > 8 ? itemId.substring(itemId.length() - 8) : itemId;
        terminal.info(prefixForCriticality(criticality)
                + " inbox: " + title
                + "  (" + criticality + ", id=…" + shortId + ")"
                + "  → /inbox show " + itemId);
    }

    private static String prefixForCriticality(String c) {
        return switch (c) {
            case "CRITICAL" -> "[!]";
            case "NORMAL" -> "[*]";
            case "LOW" -> "[~]";
            default -> "[?]";
        };
    }
}
