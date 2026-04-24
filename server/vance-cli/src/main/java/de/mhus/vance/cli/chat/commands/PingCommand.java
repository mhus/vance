package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends a single {@code ping} envelope — the canonical "remote" command. Uses
 * the registry-owned counter so request ids stay unique across invocations.
 */
public class PingCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public String description() {
        return "Send a ping frame to the Brain.";
    }

    @Override
    public String usage() {
        return "/ping";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        String id = "ping_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PING,
                PingData.builder().clientTimestamp(System.currentTimeMillis()).build());
        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent("ping " + id);
    }
}
