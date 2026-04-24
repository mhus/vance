package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.concurrent.atomic.AtomicInteger;

/** Delivers a user chat message to a think-process and waits for the ack. */
public class ProcessSteerCommand implements Command {

    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    public String name() {
        return "process-steer";
    }

    @Override
    public String description() {
        return "Send a chat message to a think-process in the current session.";
    }

    @Override
    public String usage() {
        return "/process-steer <processName> <message...>";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length < 2) {
            ctx.error("Usage: " + usage());
            return;
        }
        String processName = args[0];
        String content = joinRest(args, 1);
        String id = "process-steer_" + requestCounter.incrementAndGet();
        WebSocketEnvelope envelope = WebSocketEnvelope.request(
                id,
                MessageType.PROCESS_STEER,
                ProcessSteerRequest.builder()
                        .processName(processName)
                        .content(content)
                        .build());

        ctx.expectReply(id, reply -> onReply(ctx, reply));

        if (!ctx.connection().send(envelope)) {
            ctx.error("Not connected — /connect first.");
            return;
        }
        ctx.sent(MessageType.PROCESS_STEER + " processName=" + processName
                + " content=" + preview(content));
    }

    private void onReply(CommandContext ctx, WebSocketEnvelope reply) {
        if (ReplyHandlers.handledAsError(ctx, name(), reply)) {
            return;
        }
        ProcessSteerResponse resp = ctx.parseData(reply.getData(), ProcessSteerResponse.class);
        if (resp == null) {
            ctx.error(name() + ": empty reply");
            return;
        }
        ctx.received("process-steer ack: " + resp.getProcessName()
                + " (status=" + resp.getStatus() + ")");
    }

    private static String joinRest(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String preview(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
