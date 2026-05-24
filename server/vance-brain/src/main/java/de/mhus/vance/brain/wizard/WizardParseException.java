package de.mhus.vance.brain.wizard;

/** Thrown by {@link WizardLoader} when a wizard YAML is malformed or fails Pebble compilation. */
public class WizardParseException extends RuntimeException {
    public WizardParseException(String message) {
        super(message);
    }

    public WizardParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
