package de.mhus.vance.brain.sourceconfig;

/** A source-configuration document that is not readable as one. */
public class SourceConfigParseException extends RuntimeException {

    public SourceConfigParseException(String message) {
        super(message);
    }

    public SourceConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
