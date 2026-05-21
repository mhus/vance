package de.mhus.vance.shared.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strip Markdown formatting for text-to-speech rendering.
 *
 * <p>Spec: specification/inline-and-embedded-content.md §10.3.
 *
 * <p>Voice clients (mobile-voice, foot-with-TTS, future web-voice)
 * call {@link #strip(String)} on the engine's raw Markdown message
 * before handing it to the system / cloud TTS synthesiser. The
 * engine output stays uniform — channel-specific rendering is the
 * client's responsibility.
 *
 * <p>Rules (v1, locale {@code de}):
 * <ul>
 *   <li>Markdown links {@code [text](url)} → {@code text}
 *       (URL dropped). Image-links {@code ![alt](url)} → {@code alt}.
 *       Empty link text → {@code "Link zu <host>"}.</li>
 *   <li>Fenced code blocks {@code ```kind\n...\n```} → "(Code-Block
 *       mit N Zeilen)". Body is not read aloud — avoids TTS reading
 *       JSON/SQL/Brainfuck.</li>
 *   <li>Tables (pipe syntax) → "(Tabelle mit X Zeilen, Y Spalten)".</li>
 *   <li>Headers ({@code #}/{@code ##}/...) → plain text + sentence-end
 *       period to hint a TTS pause.</li>
 *   <li>Bullet lists (- / *) → "Erstens: …; Zweitens: …; …".</li>
 *   <li>Numbered lists (1. / 2. / ...) → "Eins: …; Zwei: …; …".</li>
 *   <li>Bold/Italic/Strikethrough markers → stripped, text kept.</li>
 *   <li>Inline code {@code `x`} → {@code x} (markers removed).</li>
 *   <li>Horizontal rule ({@code ---}, {@code ***}) → ". ." (pause).</li>
 *   <li>HTML tags → stripped. Footnote refs → stripped.</li>
 * </ul>
 *
 * <p>Idempotent on plain text. Threadsafe (static methods, no state).
 */
public final class MarkdownToSpeech {

    private MarkdownToSpeech() {}

    // ── Patterns ─────────────────────────────────────────────────

    /** Fenced code block — opening fence with optional lang, then body until matching fence. */
    private static final Pattern FENCED = Pattern.compile(
            "(?ms)^( {0,3})(```+|~~~+)([^\\n]*)\\n(.*?)\\n\\1\\2[^\\n]*$");

    /** Pipe-table block (header + separator + body rows). */
    private static final Pattern TABLE = Pattern.compile(
            "(?m)^\\|.+\\|\\s*\\n\\s*\\|[\\s|:\\-]+\\|\\s*\\n(?:\\s*\\|.+\\|\\s*\\n?)*");

    /** Image link: ![alt](url) — capture alt. */
    private static final Pattern IMAGE_LINK = Pattern.compile(
            "!\\[([^\\]]*)\\]\\(([^)]*)\\)");

    /** Regular link: [text](url) — capture text + url. */
    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]*)\\]\\(([^)]*)\\)");

    /** ATX heading: # text up to six levels. */
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^ {0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");

    /** Bullet list item: - / * / +. */
    private static final Pattern BULLET_ITEM = Pattern.compile(
            "(?m)^ {0,3}[*+\\-]\\s+(.*)$");

    /** Numbered list item: 1. text. */
    private static final Pattern ORDERED_ITEM = Pattern.compile(
            "(?m)^ {0,3}\\d+[.)]\\s+(.*)$");

    /** Horizontal rule: 3+ of the same dash/asterisk/underscore, optional spaces. */
    private static final Pattern HRULE = Pattern.compile(
            "(?m)^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");

    /** Inline-code, bold, italic markers. */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_ITALIC = Pattern.compile("([*_]{1,3})(\\S(?:.*?\\S)?)\\1");
    private static final Pattern STRIKE = Pattern.compile("~~([^~]+)~~");

    /** HTML tags and footnote refs. */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern FOOTNOTE_REF = Pattern.compile("\\[\\^[^\\]]+\\]");

    /** Blockquote marker. */
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?m)^\\s*>\\s?");

    private static final String[] ORDINALS_DE = {
            "Erstens", "Zweitens", "Drittens", "Viertens", "Fünftens",
            "Sechstens", "Siebtens", "Achtens", "Neuntens", "Zehntens",
    };

    private static final String[] NUMBERS_DE = {
            "Eins", "Zwei", "Drei", "Vier", "Fünf",
            "Sechs", "Sieben", "Acht", "Neun", "Zehn",
    };

    /**
     * Strip Markdown formatting and return voice-friendly plain text.
     */
    public static String strip(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String s = markdown;

        // ── 1. Fenced code blocks → "(Code-Block mit N Zeilen)"
        s = replaceMatched(s, FENCED, m -> {
            String body = m.group(4);
            int lines = body.isEmpty() ? 0 : body.split("\\n", -1).length;
            return "(Code-Block mit " + lines + " Zeilen)";
        });

        // ── 2. Pipe-tables → "(Tabelle mit X Zeilen, Y Spalten)"
        s = replaceMatched(s, TABLE, m -> {
            String block = m.group();
            String[] lines = block.split("\\n");
            int rows = 0;
            int cols = 0;
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i].trim();
                if (ln.isEmpty()) continue;
                // skip separator row (---|---)
                if (i == 1 && ln.matches("\\|[\\s|:\\-]+\\|")) continue;
                rows++;
                if (cols == 0) {
                    // count columns from header row
                    String inner = ln.replaceAll("^\\||\\|$", "");
                    cols = inner.split("\\|", -1).length;
                }
            }
            return "(Tabelle mit " + rows + " Zeilen, " + cols + " Spalten)";
        });

        // ── 3. Image links → alt; regular links → text (or "Link zu <host>")
        s = replaceMatched(s, IMAGE_LINK, m -> {
            String alt = m.group(1).trim();
            return alt.isEmpty() ? "Bild" : alt;
        });
        s = replaceMatched(s, LINK, m -> {
            String text = m.group(1).trim();
            if (!text.isEmpty()) return text;
            String url = m.group(2).trim();
            String host = extractHost(url);
            return host.isEmpty() ? "Link" : "Link zu " + host;
        });

        // ── 4. Headers → "text." (sentence-end period as TTS pause hint)
        s = replaceMatched(s, HEADING, m -> {
            String text = m.group(1).trim();
            if (text.isEmpty()) return "";
            return text.endsWith(".") || text.endsWith("?") || text.endsWith("!")
                    ? text
                    : text + ".";
        });

        // ── 5. Horizontal rules → ". ."
        s = HRULE.matcher(s).replaceAll(". .");

        // ── 6. Lists → "Erstens: …; Zweitens: …"
        s = collapseList(s, BULLET_ITEM, ORDINALS_DE);
        s = collapseList(s, ORDERED_ITEM, NUMBERS_DE);

        // ── 7. Blockquote marker stripped, content kept
        s = BLOCKQUOTE.matcher(s).replaceAll("");

        // ── 8. Inline markers
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = BOLD_ITALIC.matcher(s).replaceAll("$2");

        // ── 9. HTML tags + footnote refs
        s = HTML_TAG.matcher(s).replaceAll("");
        s = FOOTNOTE_REF.matcher(s).replaceAll("");

        // ── 10. Collapse leftover whitespace
        s = s.replaceAll("[ \\t]+", " ")
              .replaceAll("\\n{3,}", "\n\n")
              .trim();
        return s;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private interface MatchReplacer {
        String apply(Matcher m);
    }

    private static String replaceMatched(String input, Pattern p, MatchReplacer fn) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String extractHost(String url) {
        if (url.isEmpty()) return "";
        try {
            int schemeEnd = url.indexOf("://");
            String rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);
            int question = rest.indexOf('?');
            if (question >= 0) rest = rest.substring(0, question);
            return rest;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Collapse consecutive list items into a single sentence with
     * connector words (Erstens / Zweitens or Eins / Zwei). Items
     * past the connector table fall back to their plain text.
     */
    private static String collapseList(String input, Pattern itemPattern, String[] connectors) {
        String[] lines = input.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> currentItems = new ArrayList<>();

        for (String line : lines) {
            Matcher m = itemPattern.matcher(line);
            if (m.matches()) {
                currentItems.add(m.group(1).trim());
            } else {
                if (!currentItems.isEmpty()) {
                    out.append(joinList(currentItems, connectors));
                    out.append('\n');
                    currentItems.clear();
                }
                out.append(line).append('\n');
            }
        }
        if (!currentItems.isEmpty()) {
            out.append(joinList(currentItems, connectors));
            out.append('\n');
        }
        // Drop the trailing newline introduced above.
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') out.setLength(len - 1);
        return out.toString();
    }

    private static String joinList(List<String> items, String[] connectors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("; ");
            String connector = i < connectors.length ? connectors[i] : "Weitens";
            sb.append(connector).append(": ").append(items.get(i));
        }
        return sb.toString();
    }
}
