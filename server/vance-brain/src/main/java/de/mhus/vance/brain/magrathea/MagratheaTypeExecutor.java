package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import java.util.Optional;

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
