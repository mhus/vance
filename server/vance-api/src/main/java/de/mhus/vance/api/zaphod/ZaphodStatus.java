package de.mhus.vance.api.zaphod;

/**
 * Top-level state of a Zaphod process. Distinct from
 * {@code ThinkProcessStatus} — the engine maps internal phase to
 * external status (see spec §11).
 */
public enum ZaphodStatus {
    /** Heads are being spawned + driven sequentially. */
    SPAWNING,

    /** Heads are running (transient between turn-cursor moves). */
    RUNNING,

    /** All heads done; synthesizer LLM-call in flight. */
    SYNTHESIZING,

    /** Synthesis written, process complete. */
    DONE,

    /** All heads failed, or other unrecoverable error. */
    FAILED
}
