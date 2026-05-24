package de.mhus.vance.api.marvin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Output of a worker's POST_CHILDREN-phase LLM call. Reached
 * after all NEEDS_SUBTASKS-spawned children have terminated.
 *
 * <p>See {@code specification/marvin-engine.md} §4.3.
 */
public record PostChildrenOutput(
        PostChildrenAction action,
        @Nullable List<NewTaskSpec> newTasks,
        @Nullable String problem,
        @Nullable String reason) {}
