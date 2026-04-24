package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectListRequest;
import de.mhus.vance.api.ws.ProjectListResponse;
import de.mhus.vance.api.ws.ProjectSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Lists projects in the current tenant, optionally filtered by project group. */
public class ProjectListCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "project-list";
    }

    @Override
    public String description() {
        return "List projects in the current tenant.";
    }

    @Override
    public String usage() {
        return "/project-list [projectGroupId]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length > 1) {
            ctx.error("Usage: " + usage());
            return;
        }
        String projectGroupId = args.length == 1 ? args[0] : null;
        String id = "project-list_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PROJECT_LIST,
                ProjectListRequest.builder().projectGroupId(projectGroupId).build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.PROJECT_LIST
                + (projectGroupId == null ? "" : " projectGroupId=" + projectGroupId));
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        ProjectListResponse resp = ctx.parseData(reply.getData(), ProjectListResponse.class);
        if (resp == null || resp.getProjects() == null || resp.getProjects().isEmpty()) {
            ctx.received("no projects");
            return;
        }
        ctx.received(String.format("%-24s %-24s %-10s %s",
                "NAME", "GROUP", "ENABLED", "TITLE"));
        for (ProjectSummary p : resp.getProjects()) {
            ctx.received(String.format("%-24s %-24s %-10s %s",
                    truncate(p.getName(), 24),
                    truncate(Objects.toString(p.getProjectGroupId(), ""), 24),
                    p.isEnabled() ? "yes" : "no",
                    Objects.toString(p.getTitle(), "")));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
