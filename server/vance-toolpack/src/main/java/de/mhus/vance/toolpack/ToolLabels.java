package de.mhus.vance.toolpack;

/**
 * The tool labels engines and recipes select on by name. Labels are free
 * strings on {@link Tool#labels()}, and an unknown {@code @label} selector
 * silently expands to nothing — so every label the runtime itself relies on
 * is named here once instead of being spelled at each use site.
 *
 * <p><b>Release decision.</b> Every built-in tool carries exactly one of
 * {@link #WORKER}, {@link #OPERATOR} and {@link #INTERNAL}. The tool's author decides once,
 * where the knowledge sits; engines then take the released set as a whole
 * instead of naming each tool. A tool without the decision fails the build
 * (see {@code ToolReleaseDecisionTest}), so a new tool cannot silently stay
 * invisible to the workers — nor silently reach them.
 */
public final class ToolLabels {

    private ToolLabels() {}

    /**
     * Released for the worker engines (Ford, Frankie). A worker takes every
     * tool carrying this label into its pool — reachable, deferred unless
     * the engine core or the recipe promotes it. Not a permission: every
     * call is still checked against the caller's grants.
     */
    public static final String WORKER = "worker";

    /**
     * Deliberately not released for workers: setup, administration and
     * authoring surfaces (settings writes, permissions, kits, hooks, tool
     * packs, tenant/user maintenance) that belong to an operator or a
     * creator agent, not to a task worker.
     */
    public static final String OPERATOR = "operator";

    /**
     * Owned by one engine and meaningless outside it: its control surface
     * (Trillian's session/task/user tools, Agrajag's health and probe tools,
     * Frankie's plan, Arthur's {@code respond}, Eddie's hub navigation). The
     * owning engine reaches it by name in its allow-set or by role gate —
     * never through a label pool.
     */
    public static final String INTERNAL = "internal";

    // ── Effect labels: what a tool does. Recipes select on them
    //    (`allowedToolsDefer: ["@write"]`, plan-mode strips "@executive"),
    //    and READ_ONLY drives Tool#safety(). ─────────────────────────────

    /** Pure lookup — no state mutation, no external side effect. */
    public static final String READ_ONLY = "read-only";

    /** Mutates application state (documents, scratchpads, RAG, records). */
    public static final String WRITE = "write";

    /** Orchestration / process control (spawn, steer, recipe apply). */
    public static final String EXECUTIVE = "executive";

    /** Observable effect outside Vance (web fetch, shell exec, kit apply). */
    public static final String SIDE_EFFECT = "side-effect";
}
