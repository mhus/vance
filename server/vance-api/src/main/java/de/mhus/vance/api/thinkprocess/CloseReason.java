package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Reason a think-process reached the terminal {@link ThinkProcessStatus#CLOSED}
 * state. Audit/UI metadata only — drives no lifecycle behaviour.
 *
 * <ul>
 *   <li>{@link #DONE} — goal reached, task tree finished. Batch-style
 *       engines (Vogon, Marvin) reach this naturally; reactive engines
 *       (Arthur) do not.</li>
 *   <li>{@link #STOPPED} — user, parent, or session-close cascade
 *       called {@code engine.stop}.</li>
 *   <li>{@link #STALE} — inconsistent state (engine version mismatch,
 *       client-context drift, lane-recovery exhaustion). User must
 *       decide how to proceed.</li>
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum CloseReason {
    DONE,
    STOPPED,
    STALE
}
