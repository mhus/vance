package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.RunCapability;
import java.util.Optional;
import java.util.Set;

/**
 * Per-{@link MagratheaTaskType} executor. One Spring bean per task type;
 * {@link MagratheaTaskExecutor} indexes them by {@link #type()} and
 * dispatches each {@code TaskStartedRecord}-marked task to the matching
 * implementation.
 *
 * <p>Sync executors (condition, terminal, tool, script, jeltz-quick)
 * return {@code Optional.of(outcome)} and the dispatcher publishes the
 * resulting {@link TaskCompletedEvent} immediately. Async executors
 * (agent_task with reactive engine, gate_task, timer_task, workflow_task)
 * return {@link Optional#empty()} and signal completion later via a
 * dedicated {@code @EventListener} that publishes the event itself.
 *
 * <p>See plan §4.0 (uniform task lifecycle).
 */
public interface MagratheaTypeExecutor {

    /** Which {@code type:} value in the YAML this executor handles. */
    MagratheaTaskType type();

    /**
     * What the run must be bound to for this executor to be able to work at
     * all — checked once when the run starts, against the binding it was
     * started with.
     *
     * <p>Empty for almost every type, and that is the point: this is not a
     * place to express which tasks <em>suit</em> which kind of plan. It
     * answers only "would this state be impossible here", so that a plan
     * that cannot finish is refused while someone is still watching, rather
     * than failing days later on a branch nobody predicted.
     *
     * <p>A state can waive the check by declaring
     * {@code catch: { capability_missing: … }} — then the impossibility is
     * an outcome the author has planned for.
     *
     * <p>The requirement may depend on the state's own spec, so the spec is
     * passed in: a gate that raises a question in a conversation needs an
     * owner process, the same gate left to the inbox needs nothing.
     */
    default Set<RunCapability> requires(
            de.mhus.vance.shared.magrathea.MagratheaStateSpec state) {
        return Set.of();
    }

    /**
     * Run the type-specific work for the given task. Implementations
     * are called on the project lane thread and must not block beyond
     * the work they do synchronously.
     *
     * @return {@link Optional#of} with the synchronous outcome, or
     *         {@link Optional#empty} if completion will arrive
     *         asynchronously via a listener.
     */
    Optional<TaskOutcome> execute(MagratheaTaskContext context);
}
