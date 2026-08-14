package de.mhus.vance.brain.script;

import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code vance.workflow} surface needs beyond the tool bus:
 * the journal projector (a bean, absent when Magrathea is disabled) and
 * the run this script is executing inside (per call, absent everywhere
 * but a {@code script_task}).
 *
 * <p>One carrier rather than two constructor parameters — the two values
 * are only ever needed together, and {@link VanceScriptApi}'s constructor
 * telescoping is long enough already.
 *
 * <p>{@code null} for the host as a whole is the same thing as both
 * fields being null: {@code vance.workflow.start(...)} still works (it
 * goes through the tool bus like any other tool call),
 * {@code vance.workflow.status(...)} refuses, and
 * {@code vance.workflow.current} is null.
 */
public record ScriptWorkflowHost(
        @Nullable MagratheaStateProjector projector,
        @Nullable ScriptWorkflowRun run) {

    /** Host with a projector but no enclosing run — the ordinary script case. */
    public static ScriptWorkflowHost of(@Nullable MagratheaStateProjector projector) {
        return new ScriptWorkflowHost(projector, null);
    }
}
