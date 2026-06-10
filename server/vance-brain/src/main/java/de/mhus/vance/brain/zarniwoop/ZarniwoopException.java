package de.mhus.vance.brain.zarniwoop;

/**
 * Raised by Zarniwoop when a precondition for dispatch is not met
 * (missing project scope, invalid endpoint config, etc.). The tool
 * layer translates these into {@code ToolException} so the LLM sees a
 * usable error string in its tool-result.
 */
public class ZarniwoopException extends RuntimeException {

    public ZarniwoopException(String message) {
        super(message);
    }

    public ZarniwoopException(String message, Throwable cause) {
        super(message, cause);
    }
}
