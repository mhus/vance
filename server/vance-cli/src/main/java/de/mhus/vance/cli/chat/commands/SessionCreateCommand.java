package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionCreateResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates a new session bound to the current WebSocket connection. */
public class SessionCreateCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "session-create";
    }

    @Override
    public String description() {
        return "Create a new session on a project and bind it to this connection.";
    }

    @Override
    public String usage() {
        return "/session-create <projectId>";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length != 1) {
            ctx.error("Usage: " + usage());
            return;
        }
        String projectId = args[0];
        String id = "session-create_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.SESSION_CREATE,
                SessionCreateRequest.builder().projectId(projectId).build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.SESSION_CREATE + " projectId=" + projectId);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        SessionCreateResponse resp = ctx.parseData(reply.getData(), SessionCreateResponse.class);
        if (resp == null) {
            ctx.error(name() + ": empty reply");
            return;
        }
        ctx.connection().bindSession(resp.getSessionId(), resp.getProjectId());
        ctx.received("session created: " + resp.getSessionId()
                + " (project=" + resp.getProjectId() + ") — bound to this connection");
    }
}
