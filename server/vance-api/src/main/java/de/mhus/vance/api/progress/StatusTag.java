package de.mhus.vance.api.progress;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Classification of a {@link StatusPayload} ping. Drives client-side
 * rendering (icon, color) and lets the user filter / dim categories
 * (e.g. mute file-IO chatter).
 *
 * <p>Tags are short hints; {@link StatusPayload#getText()} carries the
 * actual human-readable message.
 */
@GenerateTypeScript("progress")
public enum StatusTag {
    /** A tool invocation has started — see {@link StatusPayload#getText()} for the tool name. */
    TOOL_START,
    /** A tool invocation has finished. */
    TOOL_END,
    /** Web-search call with a concrete query. */
    SEARCH,
    /** HTTP fetch of an external resource. */
    FETCH,
    /** Writing to a file (workspace or local). */
    FILE_WRITE,
    /** Reading a file. */
    FILE_READ,
    /** Spawning or steering a sub-process. */
    DELEGATING,
    /** Process is parked waiting for user input (inbox, gate, etc.). */
    WAITING,
    /**
     * AI provider resilience event — transient failure being retried, or
     * the chain advancing to the next fallback model. Carries a human-
     * readable explanation in {@link StatusPayload#getText()} so the user
     * understands why a turn is taking longer than usual. Goes through
     * the {@code NORMAL} progress level (only {@link #INFO} is filtered
     * there).
     */
    PROVIDER,
    /** Marvin task-tree node finished — {@link StatusPayload#getUsage()} carries the node subtotal. */
    NODE_DONE,
    /** Vogon phase completed — {@link StatusPayload#getUsage()} carries the phase subtotal. */
    PHASE_DONE,
    /** Catch-all for engine-emitted asides that don't fit a specific tag. */
    INFO
}
