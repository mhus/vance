package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Lists the caller's sessions, optionally filtered by project. */
public class SessionListCommand implements Command {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "session-list";
    }

    @Override
    public String description() {
        return "List sessions for the current user.";
    }

    @Override
    public String usage() {
        return "/session-list [projectId]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length > 1) {
            ctx.error("Usage: " + usage());
            return;
        }
        String projectId = args.length == 1 ? args[0] : null;
        String id = "session-list_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.SESSION_LIST,
                SessionListRequest.builder().projectId(projectId).build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.SESSION_LIST + (projectId == null ? "" : " projectId=" + projectId));
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        SessionListResponse resp = ctx.parseData(reply.getData(), SessionListResponse.class);
        if (resp == null || resp.getSessions() == null || resp.getSessions().isEmpty()) {
            ctx.received("no sessions");
            return;
        }
        ctx.received(String.format("%-24s %-20s %-10s %-11s %s",
                "SESSION", "PROJECT", "STATUS", "LAST SEEN", "NAME"));
        for (SessionSummary s : resp.getSessions()) {
            ctx.received(String.format("%-24s %-20s %-10s %-11s %s%s",
                    truncate(s.getSessionId(), 24),
                    truncate(Objects.toString(s.getProjectId(), ""), 20),
                    truncate(Objects.toString(s.getStatus(), ""), 10),
                    TIME.format(Instant.ofEpochMilli(s.getLastActivityAt())),
                    Objects.toString(s.getDisplayName(), ""),
                    s.isBound() ? " (bound)" : ""));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
