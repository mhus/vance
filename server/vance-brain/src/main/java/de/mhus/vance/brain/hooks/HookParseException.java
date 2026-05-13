package de.mhus.vance.brain.hooks;

/**
 * Thrown when a hook YAML fails the {@link HookYamlParser} contract —
 * unknown event, wrong shape, missing required field, Pebble compile
 * error in {@code prompt}, etc. Caller decides whether to abort
 * bootstrap or simply skip this one document.
 */
public class HookParseException extends RuntimeException {

    public HookParseException(String message) {
        super(message);
    }

    public HookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
