package de.mhus.vance.brain.command;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.springframework.stereotype.Component;

/**
 * Diagnostic {@code echo} verb — the built-in command that proves the
 * channel end-to-end without any engine semantics. Returns the command's
 * arguments verbatim as the result value, always {@code OK}.
 *
 * <p>Kept permanently (like {@code ping}) as a connectivity probe:
 * {@code //echo hello} round-trips through the WS handler, the lane and
 * the dispatcher. Real engine verbs are added separately.
 */
@Component
public class SystemEchoCommandHandler implements EngineCommandHandler {

    @Override
    public String verb() {
        return "echo";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        return EngineCommandResult.ok("echo", command.args());
    }
}
