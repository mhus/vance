package de.mhus.vance.anus.access;

/**
 * Thrown by {@link AccessService#requireAuthorized()} (and the AOP aspect for
 * {@link RequiresAuth}) when a protected command is invoked without an active
 * login or after the sliding-window timeout has expired.
 */
public class NotAuthorizedException extends RuntimeException {

    public NotAuthorizedException(String message) {
        super(message);
    }
}
