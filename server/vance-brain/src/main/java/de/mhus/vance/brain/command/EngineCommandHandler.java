package de.mhus.vance.brain.command;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * SPI for a single control-plane command verb. Implementations are
 * Spring beans; {@link EngineCommandDispatcher} indexes them by
 * {@link #verb()} and routes matching {@link EngineCommand}s here.
 *
 * <p>Handlers run <b>on the process lane</b> (serialized with turns), so
 * they may read and mutate the {@link ThinkProcessDocument} without
 * racing an in-flight turn. They must be side-effect-clean on failure —
 * throwing is caught by the dispatcher and reported as
 * {@link de.mhus.vance.api.command.EngineCommandOutcome#ERROR}.
 *
 * <p>The verb vocabulary is intentionally open and grows per engine; see
 * {@code planning/engine-commands.md} §2.4.
 */
public interface EngineCommandHandler {

    /**
     * The exact verb this handler owns, optionally namespaced
     * ({@code namespace.verb}, e.g. {@code status.set}). Must be unique
     * across all handler beans — a collision fails the brain at boot.
     */
    String verb();

    /**
     * Processes the command. The {@code process} is a fresh read taken
     * on the lane immediately before dispatch.
     */
    EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command);
}
