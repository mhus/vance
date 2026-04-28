package de.mhus.vance.brain.vance.activity;

/**
 * Coarse classification of Activity-Log entries. Used both for
 * peer-recap rendering and (future) statistics.
 */
public enum VanceActivityKind {

    /** Vance created a new user project. */
    PROJECT_CREATED,

    /** Vance archived a user project. */
    PROJECT_ARCHIVED,

    /** Vance switched the active-project context. */
    PROJECT_SWITCHED,

    /** Vance sent a message to a worker project's chat. */
    PROCESS_STEERED,

    /** Vance observed a child worker process change status. */
    PROCESS_STATUS_CHANGED,

    /** Vance imported a document or created a text document. */
    DOC_CREATED,

    /** Vance posted an inbox item to the user. */
    INBOX_POSTED,

    /** A user statement Vance considered worth keeping. */
    USER_STATEMENT,

    /** Generic note Vance added (debug, audit, fallback). */
    NOTE
}
