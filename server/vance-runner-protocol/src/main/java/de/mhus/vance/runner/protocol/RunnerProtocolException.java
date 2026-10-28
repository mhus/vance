package de.mhus.vance.runner.protocol;

/**
 * Something about an envelope or a token is not acceptable. {@link #kind()} tells the
 * transport layer which answer to give — the runner, for one, cannot answer a request
 * it could not read with anything but a bare HTTP status.
 */
public class RunnerProtocolException extends RuntimeException {

    /** What went wrong, coarsely. */
    public enum Kind {
        /** The bytes are not an age file for this key (or not an age file at all). */
        NOT_FOR_US,
        /** Opened fine, but the content is not a valid frame/header. */
        MALFORMED,
        /** A protocol version this side does not speak. */
        UNSUPPORTED_VERSION,
        /** A response that does not answer the request it was read for. */
        WRONG_REPLY,
        /** The token is invalid, expired, mis-addressed or replayed. */
        TOKEN
    }

    private final Kind kind;

    public RunnerProtocolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public RunnerProtocolException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
