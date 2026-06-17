package de.mhus.vance.brain.ursahooks;

/**
 * Thrown when a hook YAML fails the {@link UrsaHookYamlParser} contract —
 * unknown event, wrong shape, missing required field, Pebble compile
 * error in {@code prompt}, etc. Caller decides whether to abort
 * bootstrap or simply skip this one document.
 */
public class UrsaHookParseException extends RuntimeException {

    public UrsaHookParseException(String message) {
        super(message);
    }

    public UrsaHookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
