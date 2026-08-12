package de.mhus.vance.shared.settings;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The deny-list grammar shared by every operator-configured setting-key
 * policy: comma-separated entries, each either an exact key or a prefix
 * ending in {@code *}.
 *
 * <p>Deliberately not a full glob. These lists are security configuration
 * and have to stay readable at a glance — a reviewer must be able to see
 * what {@code ai.provider.*} covers without simulating a matcher.
 *
 * <p>Extracted so the write-side ({@link AgentSettingKeyPolicy}) and the
 * read-side ({@link SecretReferenceKeyPolicy}) cannot drift apart in how
 * they interpret a pattern. The <em>lists</em> stay separate on purpose —
 * "an agent may not write this" and "no reference may resolve this" are
 * different questions, and tying them together would mean widening one
 * silently widens the other.
 */
public final class SettingKeyPatterns {

    /** Suffix marking a prefix pattern. */
    private static final String WILDCARD = "*";

    private SettingKeyPatterns() {}

    /** Splits the configured string into trimmed, non-empty patterns. */
    public static List<String> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Whether {@code key} matches any of {@code patterns}. */
    public static boolean matches(List<String> patterns, @Nullable String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith(WILDCARD)) {
                if (key.startsWith(pattern.substring(0, pattern.length() - WILDCARD.length()))) {
                    return true;
                }
            } else if (pattern.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
