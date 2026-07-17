package de.mhus.vance.brain.template;

/**
 * Thrown when a template definition or its body cannot be parsed /
 * resolved. The controller maps it to HTTP 500 (the template ships
 * broken); {@link TemplateLoader#listAll} swallows it per-entry with a
 * WARN so one malformed template never hides the rest.
 */
public class TemplateParseException extends RuntimeException {

    public TemplateParseException(String message) {
        super(message);
    }

    public TemplateParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
