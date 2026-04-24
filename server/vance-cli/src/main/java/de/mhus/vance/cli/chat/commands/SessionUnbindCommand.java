package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Releases the connection's binding to its session without closing
 * either. After this the connection is back to session-less state, so
 * {@code /session-create}, {@code /session-resume}, or
 * {@code /session-bootstrap} are allowed again. The session itself
 * stays OPEN and can be resumed later.
 */
public class SessionUnbindCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "session-unbind";
    }

    @Override
    public String description() {
        return "Release the binding between this connection and its session "
                + "without closing the session or the connection.";
    }

    @Override
    public String usage() {
        return "/session-unbind";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length != 0) {
            ctx.error("Usage: " + usage());
            return;
        }
        String id = "session-unbind_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id, MessageType.SESSION_UNBIND, null);
        ctx.expectReply(id, reply -> onReply(ctx, reply));
        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.SESSION_UNBIND);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        // Clear the client-side session + active-process state so follow-up
        // session-create/-resume starts from a clean slate.
        ctx.connection().unbindSession();
        ctx.setActiveProcessName(null);
        ctx.received("session unbound");
    }
}
