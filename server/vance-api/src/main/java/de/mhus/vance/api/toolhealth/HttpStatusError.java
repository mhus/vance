package de.mhus.vance.api.toolhealth;

/**
 * An error that knows the HTTP status the remote service answered with.
 *
 * <p>Like {@link RetryAfterError}, deliberately an interface and not an exception
 * type: a transport failure may already be some other exception and still know the
 * status. Callers that sort failures by what to do about them (a workflow's
 * {@code catch:}) read the status from here rather than from the message text.
 */
public interface HttpStatusError {

    /** The HTTP status code of the response that made the call fail. */
    int httpStatus();
}
