package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/** Resumes an existing session and binds it to this connection. */
public class SessionResumeCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "session-resume";
    }

    @Override
    public String description() {
        return "Resume an existing session and bind it to this connection.";
    }

    @Override
    public String usage() {
        return "/session-resume <sessionId>";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length != 1) {
            ctx.error("Usage: " + usage());
            return;
        }
        String sessionId = args[0];
        String id = "session-resume_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.SESSION_RESUME,
                SessionResumeRequest.builder().sessionId(sessionId).build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.SESSION_RESUME + " sessionId=" + sessionId);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        SessionResumeResponse resp = ctx.parseData(reply.getData(), SessionResumeResponse.class);
        if (resp == null) {
            ctx.error(name() + ": empty reply");
            return;
        }
        ctx.connection().bindSession(resp.getSessionId(), resp.getProjectId());
        ctx.received("session resumed: " + resp.getSessionId()
                + " (project=" + resp.getProjectId() + ")");
    }
}
