package de.mhus.vance.foot.connection;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when the Brain returns an {@code error} envelope in response to a
 * request. Carries the structured {@code errorCode} so callers can react
 * (e.g. retry on transient codes) plus the optional machine-readable
 * {@code reason} discriminator (see {@code de.mhus.vance.api.ws.ErrorData})
 * — used to tell the several {@code 409} conflict situations apart.
 */
@Getter
public class BrainException extends RuntimeException {

    private final int errorCode;
    private final @Nullable String reason;

    public BrainException(int errorCode, String message) {
        this(errorCode, message, null);
    }

    public BrainException(int errorCode, String message, @Nullable String reason) {
        super("Brain error " + errorCode + ": " + message);
        this.errorCode = errorCode;
        this.reason = reason;
    }
}
