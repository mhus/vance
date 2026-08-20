package de.mhus.vance.brain.milliways;

/**
 * The far side failed: a relay refused the message, a network call timed
 * out, an endpoint answered with garbage. Deliberately <em>not</em> a
 * {@link ShareException} — that one means "the submission is unusable and
 * the user can fix it", while this one means "we tried and it broke". The
 * distinction is what separates {@code outcome=denied} from
 * {@code outcome=failed} in the audit trail, and 422 from 502 on the wire.
 */
public class ShareTransportException extends RuntimeException {

    public ShareTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
