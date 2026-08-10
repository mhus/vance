package de.mhus.vance.simpleauth;

/**
 * Lifecycle of a {@link PermissionRequestDocument}. Only {@link #PENDING}
 * can still turn into a mutation; every other state is terminal.
 */
public enum PermissionRequestStatus {

    /** Waiting for a human decision. The only state an effect acts on. */
    PENDING,

    /** Approved and carried out. */
    APPROVED,

    /** A human said no. The mutation was never performed. */
    REJECTED,

    /**
     * Timed out, or its subject disappeared before anyone decided.
     * Expiring on subject deletion matters for short-lived service
     * accounts: a pending request naming a deleted account must not be
     * able to hit a later account that happens to reuse the name.
     */
    EXPIRED,

    /**
     * Approved, but carrying it out failed — most commonly because the
     * responder no longer held ADMIN by the time they answered. The
     * inbox item stays answered; this records that the intent did not
     * become reality.
     */
    FAILED
}
