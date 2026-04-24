package de.mhus.vance.brain.tools.client;

/**
 * Thrown when a client-registered tool fails to execute — either because
 * the client returned an error, disconnected mid-call, or didn't answer
 * within the timeout. The message is already caller-visible.
 */
public class ClientToolFailureException extends RuntimeException {

    public ClientToolFailureException(String message) {
        super(message);
    }
}
