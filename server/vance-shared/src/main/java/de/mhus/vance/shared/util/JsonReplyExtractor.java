package de.mhus.vance.shared.util;

import org.jspecify.annotations.Nullable;

/**
 * Extracts the last top-level JSON object from a free-form LLM reply.
 *
 * <p>Walks back from the final {@code '}'} and matches braces to find
 * the outermost object containing it. This is intentionally
 * permissive: examples / quotes earlier in the reply are tolerated as
 * long as the actual answer object sits at the end (which the
 * engine's prompt-postfix mechanism enforces). The extractor does
 * NOT validate JSON — callers parse with their preferred mapper and
 * surface parse errors themselves.
 *
 * <p>Used by Marvin's worker-output parser (DONE / NEEDS_SUBTASKS /
 * etc. envelope) and Vogon's scorer ({@code score}/{@code summary}/
 * {@code issues} envelope) — both engines rely on the same "last JSON
 * object wins" convention.
 */
public final class JsonReplyExtractor {

    private JsonReplyExtractor() {}

    /**
     * Returns the substring of {@code raw} representing the last
     * top-level JSON object, or {@code null} if no balanced object
     * is present. Brace-matching only — no string-literal awareness,
     * but reliable for the JSON envelopes the engines emit.
     */
    public static @Nullable String extractLastObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        int end = raw.lastIndexOf('}');
        if (end < 0) return null;
        int depth = 0;
        int start = -1;
        for (int i = end; i >= 0; i--) {
            char c = raw.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
                if (depth == 0) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) return null;
        return raw.substring(start, end + 1);
    }
}
