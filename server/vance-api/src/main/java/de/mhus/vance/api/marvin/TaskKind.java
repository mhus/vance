package de.mhus.vance.api.marvin;

/**
 * The four kinds of nodes Marvin can put into its task-tree —
 * see {@code specification/marvin-engine.md} §4.
 */
public enum TaskKind {

    /**
     * Pure planning node. Marvin itself (LLM-call) decomposes the
     * goal into sub-goals and appends them as children. No external
     * worker spawned. Synchronous within a single Marvin runTurn.
     */
    PLAN,

    /**
     * Deterministic decomposition driven by a {@code list}/{@code tree}/
     * {@code records} document. The node reads the referenced document,
     * iterates its items and appends one child per item (recursive for
     * tree) using a {@code childTemplate} carried in {@code taskSpec}.
     * No LLM call — the document <em>is</em> the plan. Synchronous,
     * see {@code specification/marvin-engine.md} §7a.
     */
    EXPAND_FROM_DOC,

    /**
     * Spawns a Ford (or other-engine) worker via a recipe. The
     * task-spec carries the recipe name and the initial steer
     * message. Asynchronous wait-point — Marvin parks the node in
     * RUNNING until the worker reports DONE/FAILED via the parent
     * notification path.
     */
    WORKER,

    /**
     * Creates an inbox item targeted at a user and waits for the
     * answer. Asynchronous wait-point — Marvin parks the node in
     * WAITING until the InboxAnswer arrives via the steer-router.
     */
    USER_INPUT,

    /**
     * Collects the artifacts of the parent's children and produces
     * a synthesized artifact at this node. Marvin itself (LLM-call).
     * Synchronous within a single Marvin runTurn — typically the
     * last sibling under a PLAN node.
     */
    AGGREGATE
}
