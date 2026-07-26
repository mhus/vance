package de.mhus.vance.foot.auth;

/**
 * Raised when a connection is attempted but no usable credential is
 * available: no valid stored access/refresh token and no configured
 * password. The message guides the user toward {@code /login}.
 */
public class AuthRequiredException extends Exception {

    public AuthRequiredException(String message) {
        super(message);
    }
}
