package de.mhus.vance.api.marvin;

/**
 * Kind of node a Marvin task-tree can carry. Marvin v2 has just
 * three: WORKER (LLM-driven, 5-phase state-machine), EXPAND_FROM_DOC
 * (deterministic fanout from a list/tree document), and USER_INPUT
 * (inbox-item wait-point).
 *
 * <p>PLAN and AGGREGATE from v1 are gone — the root is a WORKER and
 * Marvin's POST_CHILDREN phase replaces AGGREGATE. See
 * {@code specification/marvin-engine.md}.
 */
public enum TaskKind {

    /**
     * marvin-worker with the 5-phase state-machine
     * (SCOPE → REFLECT → POST_CHILDREN → CONCLUDE → VALIDATE).
     * Default for the root node and any NEEDS_SUBTASKS-spawned
     * children unless the producer explicitly overrides.
     */
    WORKER,

    /**
     * Deterministic decomposition driven by a {@code list}/{@code tree}/
     * {@code records} document. The node reads the referenced document,
     * iterates its items and appends one child per item (recursive for
     * tree) using a {@code childTemplate} carried in {@code taskSpec}.
     * No LLM call — the document <em>is</em> the plan.
     */
    EXPAND_FROM_DOC,

    /**
     * Creates an inbox item targeted at a user and waits for the
     * answer. Asynchronous wait-point — Marvin parks the node in
     * WAITING until the InboxAnswer arrives via the steer-router.
     */
    USER_INPUT
}
