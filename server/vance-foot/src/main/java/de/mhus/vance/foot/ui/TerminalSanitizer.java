package de.mhus.vance.foot.ui;

import org.jspecify.annotations.Nullable;

/**
 * Neutralizes terminal control characters in server-/LLM-supplied
 * content before it is written to the terminal (code-review F3).
 *
 * <p>Assistant / worker chunks, rendered Markdown bodies, tool diffs and
 * tool parameters are attacker-influenceable (prompt-injection, malicious
 * documents/research). An embedded {@code ESC} (0x1B) or C1 control byte
 * that reaches the terminal enables screen/scrollback spoofing, cursor
 * manipulation, OSC-52 clipboard writes and hidden text. JLine's
 * {@code AttributedStringBuilder.append} copies such bytes verbatim into
 * the content, so they survive to {@code toAnsi()} and hit the terminal.
 *
 * <p>Styling escapes are added by JLine from the {@code AttributedStyle},
 * not from the content string — so sanitizing the <i>source</i> string
 * before it is styled leaves legitimate colouring intact.
 */
public final class TerminalSanitizer {

    private TerminalSanitizer() {}

    /**
     * Removes C0 control characters (except {@code \n} and {@code \t}),
     * the C1 control range (0x80–0x9F) and {@code DEL} (0x7F). Newlines
     * and tabs are kept so multi-line content and indentation render
     * normally. Returns the input unchanged when nothing needs stripping.
     */
    public static String sanitizeContent(@Nullable String s) {
        return strip(s, true);
    }

    /**
     * Like {@link #sanitizeContent} but also strips {@code \n} and
     * {@code \t} — for single-line contexts such as an OSC window title
     * where any control character (including a newline) could break out
     * of the sequence.
     */
    public static String sanitizeStrict(@Nullable String s) {
        return strip(s, false);
    }

    private static String strip(@Nullable String s, boolean keepNewlineAndTab) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = !isControl(c, keepNewlineAndTab);
            if (keep) {
                if (out != null) out.append(c);
            } else if (out == null) {
                out = new StringBuilder(s.length()).append(s, 0, i);
            }
        }
        return out == null ? s : out.toString();
    }

    private static boolean isControl(char c, boolean keepNewlineAndTab) {
        if (keepNewlineAndTab && (c == '\n' || c == '\t')) return false;
        return c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F);
    }
}
