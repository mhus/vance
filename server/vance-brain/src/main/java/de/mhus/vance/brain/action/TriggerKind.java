package de.mhus.vance.brain.action;

/**
 * Where an {@link de.mhus.vance.api.action.TriggerAction} comes from.
 * Executors use this to pick a sandbox scope ({@code TRIGGER_SCOPED}
 * vs. {@code PROCESS_SCOPED}) and to tag event-log entries.
 *
 * <p>See {@code planning/trigger-actions.md} §8.
 */
public enum TriggerKind {

    /** Cron / one-shot scheduler tick. No spawning Process behind it. */
    SCHEDULER,

    /** Inbound webhook / HTTP event. No spawning Process behind it. */
    EVENT,

    /** Hactar workflow task. Run scope provides identity. */
    WORKFLOW_TASK,

    /** LLM-issued tool call. Process scope (the calling ThinkProcess) provides identity. */
    TOOL,

    /** Manual REST / WS call by a user. */
    MANUAL;

    /**
     * Whether actions from this trigger have an enclosing Process or
     * Workflow scope that legitimately exposes the full
     * {@code VanceScriptApi} (tool calls, sub-process spawning, …).
     */
    public boolean isProcessScoped() {
        return this == WORKFLOW_TASK || this == TOOL;
    }
}
