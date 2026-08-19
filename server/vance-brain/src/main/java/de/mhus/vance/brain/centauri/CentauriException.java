package de.mhus.vance.brain.centauri;

/**
 * Failure inside the Centauri service layer — missing project scope,
 * unusable cursor, unresolvable source.
 *
 * <p>A single failing source does <b>not</b> raise this: one dead stream
 * must not take a mixed feed down with it. Those are collected as notes on
 * the page instead.
 */
public class CentauriException extends RuntimeException {

    public CentauriException(String message) {
        super(message);
    }

    public CentauriException(String message, Throwable cause) {
        super(message, cause);
    }
}
