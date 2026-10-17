package de.mhus.vance.addon.brain.contacts;

import org.jspecify.annotations.Nullable;

/**
 * A card or a board changed between the read and the write of a
 * read-modify-write. Nothing was written; the caller re-reads and retries
 * instead of silently overwriting the other change.
 */
public class ContactConflictException extends RuntimeException {

    public ContactConflictException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
