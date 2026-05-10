package de.mhus.vance.brain.prompt;

/**
 * Thrown when a Pebble template fails to compile or render. Wraps the
 * underlying Pebble exception so callers don't need a Pebble dependency
 * on their classpath.
 *
 * <p>{@link #getMessage()} carries the Pebble diagnostic verbatim — line
 * number and the offending token are usually included by Pebble itself.
 */
public class PromptTemplateException extends RuntimeException {

    public PromptTemplateException(String message, Throwable cause) {
        super(message, cause);
    }

    public PromptTemplateException(String message) {
        super(message);
    }
}
