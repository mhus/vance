package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.thinkprocess.ProcessCreateRequest;
import de.mhus.vance.api.thinkprocess.ProcessCreateResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates a think-process in the currently bound session. */
public class ProcessCreateCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "process-create";
    }

    @Override
    public String description() {
        return "Create a think-process in the current session (e.g. zaphod).";
    }

    @Override
    public String usage() {
        return "/process-create <engine> <name>";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length != 2) {
            ctx.error("Usage: " + usage());
            return;
        }
        String engine = args[0];
        String processName = args[1];
        String id = "process-create_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PROCESS_CREATE,
                ProcessCreateRequest.builder()
                        .engine(engine)
                        .name(processName)
                        .build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.PROCESS_CREATE + " engine=" + engine + " name=" + processName);
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        ProcessCreateResponse resp = ctx.parseData(reply.getData(), ProcessCreateResponse.class);
        if (resp == null) {
            ctx.error(name() + ": empty reply");
            return;
        }
        // Make the newly-created process the target for free-text input,
        // unless the user already has an active one (don't hijack on-demand
        // spawns of secondary processes).
        if (ctx.getActiveProcessName() == null) {
            ctx.setActiveProcessName(resp.getName());
        }
        ctx.received("process created: " + resp.getName()
                + " (engine=" + resp.getEngine()
                + ", id=" + resp.getThinkProcessId()
                + ", status=" + resp.getStatus() + ")");
    }
}
