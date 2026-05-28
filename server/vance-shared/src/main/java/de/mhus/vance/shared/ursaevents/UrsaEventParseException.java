package de.mhus.vance.shared.ursaevents;

/** Surfacing-friendly wrapper for event YAML parse failures. */
public class UrsaEventParseException extends RuntimeException {
    public UrsaEventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
