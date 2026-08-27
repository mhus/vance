package de.mhus.vance.shared.user.maintenance;

/**
 * What a field that names a user <em>means</em> — which decides what a delete
 * does to it.
 *
 * <p>Only the two classes a field rewrite can express are values here. The
 * third — authority and addressability (a grant, an open decision, an unread
 * marker) — is not a field rewrite in any variant: it has to be removed or
 * handed over, and each case differs enough that those handlers are written by
 * hand. Making it a third enum value would promise the base class can handle
 * it.
 */
public enum UserReference {

    /**
     * The row exists because of that user and means nothing without them —
     * their sessions, their settings, their hub project. Deleted.
     */
    OWNED,

    /**
     * The row records something they did — authored a message, created a
     * document, acted in the feed. Tombstoned, see {@link UserTombstone}.
     */
    RECORD
}
