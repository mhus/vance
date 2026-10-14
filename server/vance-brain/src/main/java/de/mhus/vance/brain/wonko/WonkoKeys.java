package de.mhus.vance.brain.wonko;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Normalisation of topic and category keys coming from the model. */
final class WonkoKeys {

    private WonkoKeys() {}

    /**
     * Lowercase kebab segments separated by {@code /}: the model writes keys
     * in many shapes ({@code "Retry Policy"}, {@code retry_policy/Backoff});
     * matching against the register only works on one normal form.
     */
    static String topicKey(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : raw.split("/")) {
            String s = slug(segment);
            if (s.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(s);
        }
        return out.toString();
    }

    /** A single kebab segment — category names have no hierarchy. */
    static String slug(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                dash = false;
            } else if (!out.isEmpty() && !dash) {
                out.append('-');
                dash = true;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '-') {
            end--;
        }
        return out.substring(0, end);
    }
}
