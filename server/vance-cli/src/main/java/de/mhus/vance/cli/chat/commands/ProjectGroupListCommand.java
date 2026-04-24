package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectGroupListResponse;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Lists project groups in the current tenant. No request payload. */
public class ProjectGroupListCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "projectgroup-list";
    }

    @Override
    public String description() {
        return "List project groups in the current tenant.";
    }

    @Override
    public String usage() {
        return "/projectgroup-list";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length != 0) {
            ctx.error("Usage: " + usage());
            return;
        }
        String id = "projectgroup-list_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PROJECTGROUP_LIST,
                null);

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.PROJECTGROUP_LIST);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        ProjectGroupListResponse resp = ctx.parseData(reply.getData(), ProjectGroupListResponse.class);
        if (resp == null || resp.getGroups() == null || resp.getGroups().isEmpty()) {
            ctx.received("no project groups");
            return;
        }
        ctx.received(String.format("%-24s %-10s %s", "NAME", "ENABLED", "TITLE"));
        for (ProjectGroupSummary g : resp.getGroups()) {
            ctx.received(String.format("%-24s %-10s %s",
                    truncate(g.getName(), 24),
                    g.isEnabled() ? "yes" : "no",
                    Objects.toString(g.getTitle(), "")));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
