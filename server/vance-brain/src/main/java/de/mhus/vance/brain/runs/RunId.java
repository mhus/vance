package de.mhus.vance.brain.runs;

import org.jspecify.annotations.Nullable;

/**
 * A run address as it travels through URLs and links:
 * {@code <source>:<native id>}.
 *
 * <p>The prefix carries which runtime to ask. Without it the id would
 * have to be guessed from its shape — a 32-hex workflow run id against a
 * 24-hex Mongo id — which works right up until a third source arrives.
 */
public record RunId(String source, String nativeId) {

    public static RunId of(String source, String nativeId) {
        return new RunId(source, nativeId);
    }

    /** Parses {@code source:nativeId}; {@code null} when malformed. */
    public static @Nullable RunId parse(@Nullable String composite) {
        if (composite == null) return null;
        int sep = composite.indexOf(':');
        if (sep <= 0 || sep == composite.length() - 1) return null;
        return new RunId(composite.substring(0, sep), composite.substring(sep + 1));
    }

    public String composite() {
        return source + ":" + nativeId;
    }
}
