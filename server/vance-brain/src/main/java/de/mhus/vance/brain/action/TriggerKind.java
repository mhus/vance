package de.mhus.vance.brain.action;

/**
 * Where an {@link de.mhus.vance.api.action.TriggerAction} comes from.
 * Executors use this to pick a sandbox scope ({@code TRIGGER_SCOPED}
 * vs. {@code PROCESS_SCOPED}) and to tag event-log entries.
 *
 * <p>See {@code specification/trigger-actions.md} §8.
 */
public enum TriggerKind {

    /** Cron / one-shot scheduler tick. No spawning Process behind it. */
    SCHEDULER,

    /** Inbound webhook / HTTP event. No spawning Process behind it. */
    EVENT,

    /** Magrathea workflow task. Run scope provides identity. */
    WORKFLOW,

    /** LLM-issued tool call. Process scope (the calling ThinkProcess) provides identity. */
    TOOL,

    /**
     * Direct user-triggered call via the public REST / WS surface
     * (Cortex {@code POST /scripts/generate}, Admin "Run now"
     * buttons on schedulers / events, future manual ProcessCreate UIs).
     * Distinct from {@link #TOOL} (LLM-driven) and from
     * {@link #SCHEDULER} / {@link #EVENT} (automated triggers).
     * Authenticated caller's user-id is on
     * {@link TriggerContext#resolvedRunAs}.
     */
    USER,

    /**
     * Brain-internal lifecycle event (process completed, inbox item
     * created, …) routed through the {@code HookDispatcher}. Conceptually
     * the same as {@link #SCHEDULER} / {@link #EVENT}: a trigger anlass
     * that fires a configured {@link de.mhus.vance.api.action.TriggerAction}.
     * No enclosing Process — the {@code createdByUserId} of the hook
     * document provides the identity. See {@code specification/ursahooks.md}.
     */
    HOOK;

    /**
     * Whether actions from this trigger have an enclosing Process or
     * Workflow scope that legitimately exposes the full
     * {@code VanceScriptApi} (tool calls, sub-process spawning, …).
     */
    public boolean isProcessScoped() {
        return this == WORKFLOW || this == TOOL;
    }
}
