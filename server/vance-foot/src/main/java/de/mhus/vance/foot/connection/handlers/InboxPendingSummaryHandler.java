package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.inbox.InboxPendingSummaryData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code inbox-pending-summary} sent once at session-resume
 * if the user has open inbox items. One-line counter; the user can
 * dive in via {@code /inbox} when interested.
 */
@Component
public class InboxPendingSummaryHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public InboxPendingSummaryHandler(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String messageType() {
        return MessageType.INBOX_PENDING_SUMMARY;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        InboxPendingSummaryData data = json.convertValue(
                envelope.getData(), InboxPendingSummaryData.class);
        if (data == null || data.getTotalPending() <= 0) return;
        String breakdown = data.getByCriticality() == null
                ? ""
                : "  " + data.getByCriticality().entrySet().stream()
                        .filter(e -> e.getValue() != null && e.getValue() > 0)
                        .map(e -> e.getValue() + " " + e.getKey().name())
                        .collect(Collectors.joining(", "));
        terminal.info("[*] inbox: " + data.getTotalPending()
                + " pending item(s)" + breakdown
                + "  → /inbox");
    }
}
