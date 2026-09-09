package de.mhus.vance.brain.benjy;

/**
 * Benjy's task-type vocabulary (planning/benjy-engine.md §4a). The queue
 * is the state; each task carries its own successor logic in its result
 * handler. Happy-path successors are mechanics (the chain template per
 * task type) — the route call only fires at branches.
 */
public final class BenjyTaskTypes {

    /** LightLm call #0 — goal → taskType, criteria, first items. */
    public static final String INTERPRET = "interpret";

    /** LightLm call at a branch — decides the next queue operation. */
    public static final String ROUTE = "route";

    /** Spawn a focused Ford worker for one item (async — reply arrives via pending queue). */
    public static final String DO = "do";

    /** Mechanical verification — exec command, ground truth, no LLM. */
    public static final String CHECK = "check";

    /** LightLm call — item result vs acceptance criteria. */
    public static final String EVALUATE = "evaluate";

    /** Terminal gate — goal level: did we achieve what the asker meant? */
    public static final String REFLECT = "reflect";

    /** Mechanics — mark an item completed, project to todos, journal. */
    public static final String CLOSE_ITEM = "close_item";

    /** Mechanics — build the final report and close the process DONE. */
    public static final String DONE = "done";

    private BenjyTaskTypes() {}
}
