package de.mhus.vance.toolpack.feed;

/**
 * Hard failure inside a feed source — transport error, unusable response,
 * refused configuration.
 *
 * <p>Thrown rather than returned so the dispatcher can hand it to the
 * failure tracker for classification and cooldown, the same way a failed
 * search does. A soft, expected non-answer (unsupported signal, empty page)
 * is never an exception.
 */
public class FeedException extends RuntimeException {

    public FeedException(String message) {
        super(message);
    }

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
