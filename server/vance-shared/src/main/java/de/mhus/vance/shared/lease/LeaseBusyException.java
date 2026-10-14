package de.mhus.vance.shared.lease;

/**
 * The funnel is full — no free slot within {@code waitMs}. Carries a
 * diagnostic message naming the resource, the level that rejected the
 * request and the current occupancy, so the calling agent (or operator)
 * can decide: retry later, formulate differently, or give up.
 */
public class LeaseBusyException extends RuntimeException {

    public LeaseBusyException(String message) {
        super(message);
    }
}
