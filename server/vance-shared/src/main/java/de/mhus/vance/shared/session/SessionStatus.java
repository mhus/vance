package de.mhus.vance.shared.session;

/**
 * Lifecycle state of a {@link SessionDocument}.
 */
public enum SessionStatus {

    /** Live session — can be bound by a connection or idle between connections. */
    OPEN,

    /** Explicitly closed (logout or inactivity timeout). Will not accept new bindings. */
    CLOSED
}
