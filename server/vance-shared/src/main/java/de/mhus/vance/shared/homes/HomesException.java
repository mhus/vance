package de.mhus.vance.shared.homes;

import org.jspecify.annotations.Nullable;

/**
 * Raised when a home directory cannot be provided — unwritable tree, broken
 * scope key, or a home over its size budget. Callers fail closed: a subprocess
 * that cannot get its scoped home does not run, because the fallback (the
 * process home) is exactly the shared {@code HOME} this module exists to
 * replace ({@code mhus/vance#63}).
 */
public class HomesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HomesException(String message) {
        super(message);
    }

    public HomesException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
