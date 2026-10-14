package de.mhus.vance.shared.lease;

/**
 * The lease system itself is unavailable or the requested definition is
 * not resolvable — Mongo unreachable (fail-closed: the resource is not
 * used unprotected) or a lease name that no layer defines. Distinct from
 * {@link LeaseBusyException}: busy is a healthy "come back later", this
 * is "cannot decide".
 */
public class LeaseUnavailableException extends RuntimeException {

    public LeaseUnavailableException(String message) {
        super(message);
    }

    public LeaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
