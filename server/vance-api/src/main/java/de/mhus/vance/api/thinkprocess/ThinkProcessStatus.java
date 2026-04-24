package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle status of a think-process.
 *
 * <ul>
 *   <li>{@link #READY} — idle, ready for the next lifecycle call (initial
 *       state after {@code start}, and between turns of a reactive engine).
 *   <li>{@link #RUNNING} — a lifecycle call is currently executing inside
 *       the process's lane.
 *   <li>{@link #PAUSED} — user paused explicitly.
 *   <li>{@link #BLOCKED} — the engine is waiting on user input
 *       (clarification, approval, steering).
 *   <li>{@link #SUSPENDED} — session is suspended (disconnect / pod
 *       shutdown); will resume when the session resumes.
 *   <li>{@link #DONE} — goal reached, task tree finished (batch engines
 *       only; reactive engines never reach {@code DONE}).
 *   <li>{@link #STOPPED} — terminated by user or session close.
 *   <li>{@link #STALE} — inconsistent state (engine version mismatch,
 *       client-context drift) — user must decide how to proceed.
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum ThinkProcessStatus {
    READY,
    RUNNING,
    PAUSED,
    BLOCKED,
    SUSPENDED,
    DONE,
    STOPPED,
    STALE
}
