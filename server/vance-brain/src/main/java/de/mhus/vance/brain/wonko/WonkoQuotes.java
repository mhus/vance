package de.mhus.vance.brain.wonko;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The {@code verify} step (plan §4a.3): does a quote the model gave really
 * stand in the source? Code, not model — a finding whose evidence cannot be
 * found is discarded before it reaches the user. Tolerant of whitespace,
 * case, typographic quotes, Markdown emphasis and an ellipsis between parts,
 * strict about the words themselves.
 */
final class WonkoQuotes {

    /** Shorter quotes prove nothing — "data" stands in every document. */
    static final int MIN_QUOTE_CHARS = 12;

    private WonkoQuotes() {}

    static boolean standsIn(@Nullable String source, @Nullable String quote) {
        if (source == null || quote == null) {
            return false;
        }
        String haystack = normalise(source);
        String q = normalise(quote);
        if (q.length() < MIN_QUOTE_CHARS) {
            return false;
        }
        int from = 0;
        for (String part : q.split("\\s*(\\.\\.\\.|…)\\s*")) {
            if (part.isBlank()) {
                continue;
            }
            int at = haystack.indexOf(part, from);
            if (at < 0) {
                return false;
            }
            from = at + part.length();
        }
        return true;
    }

    static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\u201C\\u201D\\u201E\\u00AB\\u00BB\"]", "")
                .replaceAll("[\\u2018\\u2019\\u201A`']", "")
                .replaceAll("[*_]", "")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("[.,;:!?]+$", "");
    }
}
