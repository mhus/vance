package de.mhus.vance.brain.command;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * SPI for a single control-plane command verb. Implementations are
 * Spring beans; {@link EngineCommandDispatcher} indexes them by
 * {@link #verb()} and routes matching {@link EngineCommand}s here.
 *
 * <p>Handlers run <b>on the process lane</b> (serialized with turns)
 * unless {@link #runsOnLane()} says otherwise, so
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
     * Whether this verb needs the addressed process's lane.
     *
     * <p>Default {@code true}, which is right for anything that reads or
     * mutates the addressed process: the lane is what keeps it from
     * racing an in-flight turn.
     *
     * <p>Returning {@code false} is for verbs that do <b>not</b> touch the
     * addressed process — pure queries, or commands whose target is a
     * different process (which then serialize on <em>that</em> process's
     * lane instead). Those must not queue behind the addressed process's
     * turn: a diagnostic verb that blocks while a turn is stuck is
     * useless exactly when it is needed, and a stop that waits for the
     * thing it is meant to interrupt is no stop at all.
     *
     * <p>A handler returning {@code false} must not mutate
     * {@code process} through a whole-document save — it would race the
     * in-flight turn, and either side's save could overwrite the other.
     * The one exception is an <b>atomic single-field update</b> that no
     * whole-document save of the process can overwrite and that the
     * engine reads fresh on its next turn: today the
     * {@code engineParamOverrides.<key>} writes of
     * {@link ModelCommandHandler} and {@link ThinkingCommandHandler}
     * ({@code ThinkProcessService#setEngineParamOverride}). A new
     * exception needs the same two guarantees and belongs in this list.
     */
    default boolean runsOnLane() {
        return true;
    }

    /**
     * Processes the command. The {@code process} is a fresh read; it is
     * taken on the lane immediately before dispatch unless
     * {@link #runsOnLane()} says otherwise.
     */
    EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command);
}
