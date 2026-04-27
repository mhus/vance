package de.mhus.vance.api.vogon;

/**
 * Vogon-checkpoint types. Each maps directly to an inbox-item type
 * on the user-interaction subsystem (see
 * {@code specification/vogon-engine.md} §2.3).
 */
public enum CheckpointType {
    /** Yes/no approval — value bool. */
    APPROVAL,
    /** Pick-one decision — value string. */
    DECISION,
    /** Free-form text — value string. */
    FEEDBACK
}
