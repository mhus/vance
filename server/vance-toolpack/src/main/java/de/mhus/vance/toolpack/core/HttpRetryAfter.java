package de.mhus.vance.toolpack.core;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

/** Reads an HTTP {@code Retry-After} header: a number of seconds or an HTTP date. */
public final class HttpRetryAfter {

    private static final long MAX_SECONDS = 365L * 24 * 3600;

    private HttpRetryAfter() {}

    /**
     * The moment the service asked to be asked again, or {@code null} when the header
     * is absent or unreadable. A moment in the past is returned as is — carrying no
     * instruction is for the caller to notice.
     */
    public static @Nullable Instant parse(@Nullable String header) {
        return parse(header, Instant.now());
    }

    /** {@link #parse(String)} against a given "now", for tests. */
    public static @Nullable Instant parse(@Nullable String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String trimmed = header.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            // A number beyond what an Instant can hold is no instruction anyone can follow; capped at a year.
            return now.plusSeconds(Math.max(-MAX_SECONDS, Math.min(MAX_SECONDS, seconds)));
        } catch (NumberFormatException notSeconds) {
            try {
                return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
            } catch (RuntimeException notADate) {
                return null;
            }
        }
    }
}
