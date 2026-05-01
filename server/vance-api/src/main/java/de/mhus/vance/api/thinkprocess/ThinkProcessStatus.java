package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle status of a think-process. Seven values; terminal state
 * carries an additional {@link CloseReason}.
 *
 * <p>See {@code specification/session-lifecycle.md} §3 for the
 * full taxonomy and {@code specification/think-engines.md} §5 for
 * the engine-side contract.
 *
 * <ul>
 *   <li>{@link #INIT} — default state right after creation, before
 *       {@code engine.start()} runs. Only {@code start} is allowed
 *       in this state.</li>
 *   <li>{@link #RUNNING} — a turn is currently executing inside the
 *       process's lane.</li>
 *   <li>{@link #IDLE} — between turns; pending-queue may be empty.
 *       Auto-wakes on a new pending message.</li>
 *   <li>{@link #BLOCKED} — engine asked the user via the inbox and
 *       is waiting for the answer. Auto-wakes on the answer's
 *       pending message — same wakeup mechanic as {@link #IDLE},
 *       different semantics ("user is dran").</li>
 *   <li>{@link #PAUSED} — user explicitly paused. <em>No</em> auto-wakeup;
 *       requires explicit {@code process-resume}.</li>
 *   <li>{@link #SUSPENDED} — system halted (session-suspend cascade,
 *       pod-shutdown, lease-loss). Wakes only on session-resume cascade
 *       or manual resume.</li>
 *   <li>{@link #CLOSED} — terminal. Carries a {@link CloseReason}
 *       for audit/UI: {@code DONE}, {@code STOPPED}, {@code STALE}.</li>
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum ThinkProcessStatus {
    INIT,
    RUNNING,
    IDLE,
    BLOCKED,
    PAUSED,
    SUSPENDED,
    CLOSED
}
