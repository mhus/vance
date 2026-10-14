package de.mhus.vance.brain.oauthserver;

import org.jspecify.annotations.Nullable;

/**
 * Cleans a client's self-chosen {@code client_name} before it is stored and
 * shown on the consent page.
 *
 * <p>Removed: control characters, format characters (Unicode {@code Cf} —
 * bidi overrides and isolates, zero-width spaces and joiners, the BOM),
 * line and paragraph separators and lone surrogates. Those are the
 * characters that let a name look like something else on screen ("Vancetope"
 * followed by an invisible right-to-left override). Runs of whitespace
 * collapse to a single space; the result is cut at a code-point boundary.
 */
final class OAuthClientNames {

    private OAuthClientNames() {}

    /** The cleaned name, or {@code null} when nothing printable remains. */
    static @Nullable String sanitize(@Nullable String raw, int maxCodePoints) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxCodePoints * 2));
        int kept = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length() && kept < maxCodePoints; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isInvisible(cp)) {
                continue;
            }
            if (pendingSpace) {
                if (kept + 1 >= maxCodePoints) {
                    break;
                }
                out.append(' ');
                kept++;
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
            kept++;
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE;
    }
}
