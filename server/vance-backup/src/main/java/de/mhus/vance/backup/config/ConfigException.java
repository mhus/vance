package de.mhus.vance.backup.config;

/**
 * Thrown by {@link ConfigLoader} when a backup configuration cannot be
 * read, parsed, or validated. The CLI maps this to exit code 4
 * ({@code planning/user-backup.md} §4.2: config error) — different from
 * exit code 2 (auth) and exit code 3 (target not writable).
 *
 * <p>The message is human-facing; it must explain what was wrong and
 * where the tool looked, but never echo the connection string back to
 * the user (the token is inside it).
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
