package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Reason a think-process reached the terminal {@link ThinkProcessStatus#CLOSED}
 * state. Audit/UI metadata only — drives no lifecycle behaviour.
 *
 * <p>See {@code specification/session-lifecycle.md} §3.
 *
 * <ul>
 *   <li>{@link #DONE} — goal reached, task tree finished. Batch-style
 *       engines (Vogon, Marvin) reach this naturally; reactive engines
 *       (Arthur) do not.</li>
 *   <li>{@link #INCOMPLETE} — the engine terminated <em>without</em>
 *       reaching its goal — e.g. a worker exhausted its tool-iteration
 *       budget mid-task. Distinct from {@link #DONE} so an orchestrator
 *       parent can tell "finished" from "gave up" and deliver an honest
 *       "couldn't complete" message instead of a stale progress note.
 *       Maps to a {@code FAILED} ProcessEvent.</li>
 *   <li>{@link #STOPPED} — user, parent, or session-close cascade
 *       called {@code engine.stop}.</li>
 *   <li>{@link #STALE} — inconsistent state (engine version mismatch,
 *       client-context drift, lane-recovery exhaustion). User must
 *       decide how to proceed.</li>
 *   <li>{@link #ARCHIVED} — session moved to {@code ARCHIVED}; engine
 *       was shut down as part of the archive cascade. Conversation
 *       history persists.</li>
 *   <li>{@link #USER_DELETE} — session was hard-deleted from the
 *       archive by an explicit user action.</li>
 *   <li>{@link #ABANDONED} — session was deemed empty/abandoned by
 *       the abandoned-detection predicate at suspend-sweep time
 *       (no Q&amp;A pair, no side-effects, no user investment) and
 *       skipped the archive directly to {@code CLOSED}.</li>
 *   <li>{@link #AUTO_CLOSE} — session reached {@code CLOSED} via the
 *       {@code onSuspend=CLOSE} sweeper path (daemon, event-driven).
 *       No user signal required.</li>
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum CloseReason {
    DONE,
    INCOMPLETE,
    STOPPED,
    STALE,
    ARCHIVED,
    USER_DELETE,
    ABANDONED,
    AUTO_CLOSE
}
