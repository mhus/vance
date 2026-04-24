package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * A think-engine algorithm. Implementations are Spring beans; the registry
 * in {@link ThinkEngineService} discovers them automatically via
 * {@code List<ThinkEngine>} autowire.
 *
 * <p>Engines are <b>stateless with respect to a concrete instance</b> — all
 * runtime data lives in the {@link ThinkProcessDocument} that's handed in
 * on every lifecycle call. Do not cache per-process state in instance
 * fields.
 *
 * <p>Lifecycle semantics and the full status set are documented in
 * {@code specification/arthur-engine.md §2} and
 * {@code specification/think-engines.md §3}.
 */
public interface ThinkEngine {

    // ─── Metadata ────────────────────────────────────────────────────────

    /** Unique, lowercase-kebab, persisted in {@code ThinkProcessDocument.thinkEngine}. */
    String name();

    /** Display name for UI / CLI. */
    String title();

    String description();

    /** SemVer used for resume-compat checks. */
    String version();

    // ─── Lifecycle ──────────────────────────────────────────────────────

    /** First entry — engine initialises its state, writes greeting / plans task-tree. */
    void start(ThinkProcessDocument process, ThinkEngineContext context);

    /** Resume from {@code SUSPENDED} or {@code PAUSED}. */
    void resume(ThinkProcessDocument process, ThinkEngineContext context);

    /** Session-driven suspend — halt at the next safe boundary, no finalisation. */
    void suspend(ThinkProcessDocument process, ThinkEngineContext context);

    /** Deliver a user / sibling-process / tool-result message to the engine. */
    void steer(ThinkProcessDocument process, ThinkEngineContext context, SteerMessage message);

    /** Final stop. Process becomes {@code STOPPED}. */
    void stop(ThinkProcessDocument process, ThinkEngineContext context);
}
