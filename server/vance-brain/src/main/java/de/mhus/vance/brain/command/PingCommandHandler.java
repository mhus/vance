package de.mhus.vance.brain.command;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.springframework.stereotype.Component;

/**
 * Engine-agnostic {@code ping} verb — answers {@code pong} on any
 * think-process. A permanent liveness probe for the {@code //verb}
 * control-plane channel, alongside {@link SystemEchoCommandHandler}.
 * See {@code planning/engine-commands.md} §2.
 */
@Component
public class PingCommandHandler implements EngineCommandHandler {

    @Override
    public String verb() {
        return "ping";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        return EngineCommandResult.ok("pong", null);
    }
}
