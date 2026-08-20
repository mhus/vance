package de.mhus.vance.brain.milliways;

/**
 * A share was refused for a reason the user can act on — a missing
 * required field, an unparseable recipient, a document too large for the
 * transport. The message is shown to the user, so it is written for them.
 *
 * <p>Transport failures are <em>not</em> this: they are the handler's own
 * exceptions, which {@link MilliwaysService} lets through after recording
 * {@code outcome=failed}.
 */
public class ShareException extends RuntimeException {

    public ShareException(String message) {
        super(message);
    }

    public ShareException(String message, Throwable cause) {
        super(message, cause);
    }
}
