package de.mhus.vance.brain.runs;

/**
 * A request about an existing run that cannot be done as asked — an unknown state name, an outcome the state has
 * no way on for, a restart point the run never reached. The run itself was found; the caller asked for something
 * wrong, so the answer is a 400, never the 404 a plain {@link IllegalArgumentException} (unknown run) maps to.
 */
public class BadRunRequestException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public BadRunRequestException(String message) {
        super(message);
    }
}
