package de.mhus.vance.foot.auth;

/**
 * The brain answered a sign-in request with something other than 200. Carries the status so a caller can
 * tell a refused credential (401) from a rate limit (429) or a server fault instead of reading the message.
 */
public class AccessTokenHttpException extends IllegalStateException {

    private final int statusCode;

    public AccessTokenHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
