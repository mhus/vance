package de.mhus.vance.shared.events;

/** Surfacing-friendly wrapper for event YAML parse failures. */
public class EventParseException extends RuntimeException {
    public EventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
