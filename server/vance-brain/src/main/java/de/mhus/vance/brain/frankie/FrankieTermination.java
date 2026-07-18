package de.mhus.vance.brain.frankie;

/**
 * Frankie-internal conventions for tool-driven loop termination.
 *
 * <p>A tool result that carries
 * {@code Map.of(..., "_terminate", true, ...)} signals "stop after
 * this batch". Frankie checks for the key on every tool-batch
 * result and exits the loop with status {@code DONE} when at least
 * one tool in the batch terminates.
 *
 * <p>Lives in Frankie until a second engine wants the same
 * convention — then it gets promoted to
 * {@code vance-api/.../tools/ToolResultConventions}.
 */
public final class FrankieTermination {

    /** Result-map key. Truthy ⇒ engine treats as terminal. */
    public static final String RESULT_TERMINATE_KEY = "_terminate";

    private FrankieTermination() {}
}
