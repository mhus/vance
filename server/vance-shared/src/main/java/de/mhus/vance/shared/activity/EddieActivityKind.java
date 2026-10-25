package de.mhus.vance.shared.activity;

/**
 * Coarse classification of Activity-Log entries. Used both for
 * peer-recap rendering and (future) statistics.
 */
public enum EddieActivityKind {

    /** Vance created a new user project. */
    PROJECT_CREATED,

    /** Vance archived a user project. */
    PROJECT_ARCHIVED,

    /** Vance brought an archived user project back. */
    PROJECT_UNARCHIVED,

    /** Vance switched the active-project context. */
    PROJECT_SWITCHED,

    /** Vance created a user account. */
    ACCOUNT_CREATED,

    /** Vance changed, disabled or re-enabled a user account. */
    ACCOUNT_CHANGED,

    /** Vance created a team. */
    TEAM_CREATED,

    /** Vance changed a team — title, enabled flag or members. */
    TEAM_CHANGED,

    /** Vance deleted a team. */
    TEAM_DELETED,

    /** Vance granted or revoked a tenant role or project membership. */
    ROLE_CHANGED,

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
