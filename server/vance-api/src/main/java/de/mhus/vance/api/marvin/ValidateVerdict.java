package de.mhus.vance.api.marvin;

/**
 * Verdict of the VALIDATE phase. See
 * {@code specification/marvin-engine.md} §4.5.
 */
public enum ValidateVerdict {
    /** Candidate result accepted — node DONE. */
    PASS,

    /** Result inadequate; re-run CONCLUDE with the validator's hint. */
    RETRY_CONCLUDE,

    /** Result inadequate; loop back to REFLECT for more data. */
    NEED_MORE_DATA,

    /** Goal cannot be met — node FAILED. */
    HARD_FAIL
}
