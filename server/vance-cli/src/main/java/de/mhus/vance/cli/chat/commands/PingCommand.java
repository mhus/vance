package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends a single {@code ping} envelope and prints the round-trip latency from
 * the matching {@code pong}.
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
        long sentAt = System.currentTimeMillis();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PING,
                PingData.builder().clientTimestamp(sentAt).build());

        ctx.expectReply(id, reply -> onReply(ctx, reply, sentAt));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent("ping " + id);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply, long sentAt) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        PongData pong = ctx.parseData(reply.getData(), PongData.class);
        long rttMs = System.currentTimeMillis() - sentAt;
        if (pong == null) {
            ctx.received("pong (rtt " + rttMs + "ms)");
            return;
        }
        long oneWayMs = pong.getServerTimestamp() - pong.getClientTimestamp();
        ctx.received("pong rtt=" + rttMs + "ms one-way=" + oneWayMs + "ms");
    }
}
