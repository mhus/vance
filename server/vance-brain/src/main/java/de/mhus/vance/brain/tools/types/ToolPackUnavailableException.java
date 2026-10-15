package de.mhus.vance.brain.tools.types;

/**
 * A tool pack cannot be materialised for this caller for an expected,
 * non-error reason — an MCP server whose OAuth login the caller (or the
 * project / tenant account) has not made yet. The registry skips the pack
 * quietly instead of logging it as broken; nothing is cached, so the pack
 * appears on the next turn after the login.
 */
public class ToolPackUnavailableException extends RuntimeException {

    public ToolPackUnavailableException(String message) {
        super(message);
    }
}
