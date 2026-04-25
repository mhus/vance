package de.mhus.vance.foot.connection;

import lombok.Getter;

/**
 * Thrown when the Brain returns an {@code error} envelope in response to a
 * request. Carries the structured {@code errorCode} so callers can react
 * (e.g. retry on transient codes).
 */
@Getter
public class BrainException extends RuntimeException {

    private final int errorCode;

    public BrainException(int errorCode, String message) {
        super("Brain error " + errorCode + ": " + message);
        this.errorCode = errorCode;
    }
}
