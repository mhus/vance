package de.mhus.vance.cli.chat;

import org.jspecify.annotations.Nullable;

/**
 * Null-safe helpers to turn exceptions into something a user can read. Java's
 * network exceptions (notably {@link java.net.ConnectException}) frequently
 * report {@code getMessage() == null}, which yields useless lines like
 * "Login failed: null" in the chat history.
 */
final class Errors {

    private Errors() {}

    /**
     * Returns a non-empty description of {@code t}. If the message is null or
     * blank, falls back to the simple class name. If a cause is present and
     * the top-level description is still empty, walks the cause chain.
     */
    static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            String inner = describe(cause);
            return t.getClass().getSimpleName() + ": " + inner;
        }
        return t.getClass().getSimpleName();
    }
}
