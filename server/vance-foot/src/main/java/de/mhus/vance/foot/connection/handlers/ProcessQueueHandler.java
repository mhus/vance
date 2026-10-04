package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.ProcessQueueNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.chat.QueuedSendState;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code process-queue} notifications — the uptake half of the
 * "active message queue" ({@code planning/active-message-queue.md} §4 P3).
 *
 * <p>The send-side "⋯ queued" note is printed by
 * {@code ChatInputService} at the persist-ack; this handler closes the
 * loop when the engine picks the message up at its next loop boundary. It
 * only speaks up for messages <em>this client</em> queued
 * ({@link QueuedSendState}) — a drain of machinery traffic (worker events,
 * parent notifications, other users' messages) stays quiet, and everything
 * else is verbose diagnostics.
 */
@Component
public class ProcessQueueHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final QueuedSendState queuedSends;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ProcessQueueHandler(ChatTerminal terminal, QueuedSendState queuedSends) {
        this.terminal = terminal;
        this.queuedSends = queuedSends;
    }

    @Override
    public String messageType() {
        return MessageType.PROCESS_QUEUE;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ProcessQueueNotification msg = json.convertValue(envelope.getData(), ProcessQueueNotification.class);
        if (msg == null) {
            return;
        }
        List<String> drained = msg.getDrainedIds() == null ? List.of() : msg.getDrainedIds();
        int mine = queuedSends.pickup(drained);
        if (mine > 0) {
            terminal.info("✓ picked up" + (mine == 1 ? " your queued message" : " " + mine + " queued messages")
                    + " — the engine is working on it now");
            return;
        }
        if (!drained.isEmpty()) {
            terminal.verbose("queue: " + drained.size() + " message(s) picked up");
            return;
        }
        int added = msg.getAdded() == null ? 0 : msg.getAdded().size();
        if (added > 0) {
            terminal.verbose("queue: " + added + " message(s) waiting");
        }
    }
}
