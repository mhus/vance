package de.mhus.vance.api.zaphod;

/**
 * Lifecycle state of a single head within a Zaphod council.
 * See {@code specification/zaphod-engine.md} §3.
 */
public enum HeadStatus {
    /** Not started yet — Zaphod hasn't reached this head in the
     *  sequential cursor. */
    PENDING,

    /** Spawn done, sub-process is being driven (transient — only
     *  visible if persistence catches it mid-step). */
    RUNNING,

    /** Reply captured, head is finished. */
    DONE,

    /** Spawn or sync-drive failed. {@code failureReason} explains. */
    FAILED
}
