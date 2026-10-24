package de.mhus.vance.shared.role.request;

/**
 * Lifecycle of a {@link RoleRequestDocument}. Only {@link #PENDING} can still
 * turn into a change; every other state is terminal.
 */
public enum RoleRequestStatus {

    /** Waiting for a human decision. The only state an effect acts on. */
    PENDING,

    /** Approved and carried out. */
    APPROVED,

    /** A human said no. The change was never made. */
    REJECTED,

    /**
     * Timed out, or its subject disappeared before anyone decided. Expiring on
     * subject deletion matters for short-lived service accounts: a pending
     * request naming a deleted account must not be able to hit a later account
     * that happens to reuse the name.
     */
    EXPIRED,

    /**
     * Approved, but carrying it out failed — most commonly because the
     * responder no longer had the authority by the time they answered, or the
     * change broke an assignment rule. The inbox item stays answered; this
     * records that the intent did not become reality.
     */
    FAILED
}
