package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;

/**
 * Small helpers shared by remote commands when parsing a reply envelope.
 * Every command that does an envelope-request wants the same first step:
 * "if the reply is an {@code error} envelope, log the code + message and
 * short-circuit". Kept as a tiny utility rather than a base class so commands
 * can still structure themselves however they want.
 */
public final class ReplyHandlers {

    private ReplyHandlers() {}

    /**
     * If {@code reply} is an {@code error} envelope, logs it via
     * {@link CommandContext#error} and returns {@code true}. Otherwise returns
     * {@code false} so the caller can proceed with the happy-path parse.
     */
    public static boolean handledAsError(CommandContext ctx, String cmdName, WebSocketEnvelope reply) {
        if (!MessageType.ERROR.equals(reply.getType())) {
            return false;
        }
        ErrorData err = ctx.parseData(reply.getData(), ErrorData.class);
        if (err == null) {
            ctx.error(cmdName + ": server error (no details)");
        } else {
            String msg = err.getErrorMessage();
            ctx.error(cmdName + ": " + err.getErrorCode()
                    + (msg == null || msg.isBlank() ? "" : " — " + msg));
        }
        return true;
    }
}
