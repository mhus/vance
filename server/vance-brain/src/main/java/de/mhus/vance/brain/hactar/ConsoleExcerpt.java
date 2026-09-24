package de.mhus.vance.brain.hactar;

import org.jspecify.annotations.Nullable;

/**
 * Line- and char-capped excerpt of a run's console tail
 * ({@link de.mhus.vance.api.hactar.HactarState#getConsoleTail()}) — the
 * shared render helper for the three visibility surfaces: the run service's
 * terminal wakeup note, the session identity's prompt status block, and the
 * {@code //hactar} diagnostics.
 */
public final class ConsoleExcerpt {

    private ConsoleExcerpt() {}

    /**
     * The last {@code maxLines} lines (capped at {@code maxChars} total,
     * tail-winning on truncation) — empty string when the tail is null or
     * blank.
     */
    public static String of(@Nullable String tail, int maxLines, int maxChars) {
        if (tail == null || tail.isBlank()) {
            return "";
        }
        String stripped = tail.strip();
        String[] lines = stripped.split("\n", -1);
        int from = Math.max(0, lines.length - Math.max(1, maxLines));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i].stripTrailing());
        }
        if (sb.length() <= Math.max(1, maxChars)) {
            return sb.toString();
        }
        return sb.substring(sb.length() - Math.max(1, maxChars));
    }
}
