package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.inbox.InboxItemDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code inbox-item-updated} push frames — currently
 * minimal (one-liner). The Lanterna-based inbox UI later will
 * subscribe to this for live list-refresh.
 */
@Component
public class InboxItemUpdatedHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public InboxItemUpdatedHandler(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.INBOX_ITEM_UPDATED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        InboxItemDto item = json.convertValue(envelope.getData(), InboxItemDto.class);
        if (item == null) return;
        terminal.verbose("inbox-update id=" + item.getId()
                + " status=" + item.getStatus()
                + " assignee=" + item.getAssignedToUserId());
    }
}
