package de.mhus.vance.api.marvin;

/**
 * Discriminator for the structured output a Marvin worker returns
 * at the end of its turn. See {@code specification/marvin-engine.md}
 * §5a for the full output contract.
 */
public enum WorkerOutcome {
    /** Worker has a final answer; {@code result} carries it. */
    DONE,

    /**
     * Worker realised the task needs further decomposition;
     * {@code newTasks[]} carries the proposed subtasks. Marvin
     * appends them as children under the worker node and marks
     * the worker DONE with the worker's {@code result} captured
     * as {@code partialResult} on the node.
     */
    NEEDS_SUBTASKS,

    /**
     * Worker cannot proceed without a user decision;
     * {@code userInput} carries the inbox-item shape. Marvin
     * inserts a USER_INPUT sibling after the worker node and
     * marks the worker DONE with {@code partialResult}.
     */
    NEEDS_USER_INPUT,

    /**
     * Worker hit a hard problem (missing tool, missing data,
     * undecidable input). {@code problem} + {@code reason}
     * explain. Marvin marks the worker FAILED.
     */
    BLOCKED_BY_PROBLEM
}
