package de.mhus.vance.foot.markdown;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.StyleParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lite markdown → ANSI renderer. Produces a list of {@link AttributedString}
 * lines, ready to be passed to JLine.
 *
 * <p>Supported subset:
 * <ul>
 *   <li>ATX headings {@code # … ######} — coloured, blank line above
 *       and below, the leading {@code #} marks are kept so the user can
 *       still see the level.</li>
 *   <li>Fenced code blocks {@code ```lang … ```} — fence lines and body
 *       in the code style; body is emitted verbatim (no inline
 *       interpretation).</li>
 *   <li>GitHub-flavoured tables (header row + separator
 *       {@code |---|---|} + body rows) — column widths computed from
 *       max content width, rendered with unicode box-drawing chars and
 *       inline formatting applied per cell.</li>
 *   <li>Blockquotes (lines starting with {@code &gt; }) — faint italic.</li>
 *   <li>Inline {@code **bold**}, {@code *italic*}/{@code _italic_},
 *       {@code `code`} — markers stripped, ANSI styles applied.</li>
 *   <li>List items {@code - …}, {@code * …}, {@code 1. …} pass through
 *       with inline formatting applied. No nested-indent special
 *       handling; the user already gets a usable indent from the
 *       markdown source.</li>
 * </ul>
 *
 * <p>Not handled: setext headings ({@code === / ---} underlines),
 * link / image syntax, footnotes, nested code fences inside tables.
 * Anything not recognised flows through {@link #renderInline} so worst
 * case the user gets the raw text with inline markers translated.
 *
 * <p>The renderer is stateless — pass the full assistant turn, get the
 * line list back. State (in-fence / in-table) lives only on the local
 * loop.
 */
@Component
public class MarkdownAnsiRenderer {

    private final @Nullable AttributedStyle headingStyle;
    private final @Nullable AttributedStyle codeStyle;
    private final @Nullable AttributedStyle blockquoteStyle;
    private final @Nullable AttributedStyle tableBorderStyle;
    private final int wrapWidth;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.*)$");

    public MarkdownAnsiRenderer(FootConfig config) {
        FootConfig.Markdown md = config.getUi().getMarkdown();
        this.headingStyle = StyleParser.parse(md.getHeading());
        this.codeStyle = StyleParser.parse(md.getCode());
        this.blockquoteStyle = StyleParser.parse(md.getBlockquote());
        this.tableBorderStyle = StyleParser.parse(md.getTableBorder());
        this.wrapWidth = md.getWrapWidth();
    }

    /**
     * Render {@code markdown} to a list of styled lines. Empty input
     * yields an empty list.
     */
    public List<AttributedString> render(String markdown) {
        List<AttributedString> out = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return out;
        String[] raw = markdown.split("\n", -1);
        int i = 0;
        while (i < raw.length) {
            String line = stripTrailing(raw[i]);
            if (FENCE.matcher(line).matches()) {
                out.add(styled(line, codeStyle));
                i++;
                while (i < raw.length) {
                    String body = stripTrailing(raw[i]);
                    if (FENCE.matcher(body).matches()) {
                        out.add(styled(body, codeStyle));
                        i++;
                        break;
                    }
                    out.add(styled(body, codeStyle));
                    i++;
                }
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                if (!out.isEmpty() && !isBlank(out.get(out.size() - 1))) {
                    out.add(AttributedString.EMPTY);
                }
                out.add(styled(line, headingStyle));
                int look = i + 1;
                if (look >= raw.length || !raw[look].isBlank()) {
                    out.add(AttributedString.EMPTY);
                }
                i++;
                continue;
            }
            if (isTableStart(raw, i)) {
                int j = collectTableEnd(raw, i);
                out.addAll(renderTable(raw, i, j));
                i = j;
                continue;
            }
            Matcher bq = BLOCKQUOTE.matcher(line);
            if (bq.matches()) {
                String body = bq.group(1);
                AttributedStringBuilder bodyBuf = new AttributedStringBuilder();
                appendInline(bodyBuf, body, blockquoteStyle);
                // Wrap to width-2 so the "│ " prefix on each line still
                // fits in the budget; then re-prefix every wrapped row.
                int prefixBudget = wrapWidth > 2 ? wrapWidth - 2 : 0;
                for (AttributedString frag : wrap(bodyBuf.toAttributedString(), prefixBudget)) {
                    AttributedStringBuilder sb = new AttributedStringBuilder();
                    if (blockquoteStyle != null) sb.style(blockquoteStyle);
                    sb.append("│ ");
                    sb.append(frag);
                    out.add(sb.toAttributedString());
                }
                i++;
                continue;
            }
            for (AttributedString frag : wrap(renderInline(line), wrapWidth)) {
                out.add(frag);
            }
            i++;
        }
        return out;
    }

    /**
     * Word-wrap a single rendered line to {@code width} columns,
     * breaking at the last space within the budget. Returns the input
     * unchanged when wrapping is disabled ({@code width <= 0}) or the
     * line already fits. A token longer than the budget is emitted as
     * one over-long line — mid-word cuts would mangle URLs and code
     * identifiers.
     */
    List<AttributedString> wrap(AttributedString line, int width) {
        List<AttributedString> out = new ArrayList<>();
        if (width <= 0 || line.length() <= width) {
            out.add(line);
            return out;
        }
        int start = 0;
        int n = line.length();
        while (start < n) {
            int remaining = n - start;
            if (remaining <= width) {
                out.add(line.subSequence(start, n));
                return out;
            }
            int hardEnd = start + width;
            int breakAt = -1;
            for (int j = hardEnd; j > start; j--) {
                if (line.charAt(j) == ' ') {
                    breakAt = j;
                    break;
                }
            }
            if (breakAt < 0) {
                // No space in the budget — consume the next word entirely
                // even if it overflows, then wrap normally from the next
                // word boundary.
                int j = hardEnd;
                while (j < n && line.charAt(j) != ' ') j++;
                out.add(line.subSequence(start, j));
                start = j;
            } else {
                out.add(line.subSequence(start, breakAt));
                start = breakAt;
            }
            // Skip the leading space(s) on the continuation line.
            while (start < n && line.charAt(start) == ' ') start++;
        }
        return out;
    }

    /* ----------------------- inline ----------------------- */

    /** Default-style inline render: bold / italic / inline-code markers translated. */
    AttributedString renderInline(String line) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        appendInline(sb, line, null);
        return sb.toAttributedString();
    }

    /**
     * Walk {@code text} char by char, emitting bold/italic/inline-code
     * runs. {@code base} is the style restored between inline runs —
     * pass {@code null} for the default render, or the surrounding
     * block's style (e.g. blockquote) so the inline runs inherit it.
     */
    private void appendInline(AttributedStringBuilder sb, String text, @Nullable AttributedStyle base) {
        AttributedStyle baseStyle = base == null ? AttributedStyle.DEFAULT : base;
        int n = text.length();
        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            // Inline code: `…`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    flush(sb, buf, baseStyle);
                    AttributedStyle s = baseStyle;
                    if (codeStyle != null) s = codeStyle;
                    sb.style(s);
                    sb.append(text, i + 1, end);
                    sb.style(baseStyle);
                    i = end + 1;
                    continue;
                }
            }
            // Bold: **…**
            if (c == '*' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i + 1) {
                    flush(sb, buf, baseStyle);
                    sb.style(baseStyle.bold());
                    appendInline(sb, text.substring(i + 2, end), baseStyle.bold());
                    sb.style(baseStyle);
                    i = end + 2;
                    continue;
                }
            }
            // Italic: *…* or _…_
            if ((c == '*' || c == '_') && i + 1 < n && text.charAt(i + 1) != c) {
                // Pick matching closing marker not preceded by a space
                char marker = c;
                int end = -1;
                int probe = i + 1;
                while (probe < n) {
                    int idx = text.indexOf(marker, probe);
                    if (idx < 0) break;
                    if (marker == '*' && idx + 1 < n && text.charAt(idx + 1) == '*') {
                        probe = idx + 2;
                        continue;
                    }
                    if (idx > i + 1 && text.charAt(idx - 1) != ' ') {
                        end = idx;
                        break;
                    }
                    probe = idx + 1;
                }
                if (end > i + 1) {
                    flush(sb, buf, baseStyle);
                    sb.style(baseStyle.italic());
                    appendInline(sb, text.substring(i + 1, end), baseStyle.italic());
                    sb.style(baseStyle);
                    i = end + 1;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(sb, buf, baseStyle);
    }

    private static void flush(AttributedStringBuilder sb, StringBuilder buf, AttributedStyle style) {
        if (buf.length() == 0) return;
        sb.style(style);
        sb.append(buf.toString());
        buf.setLength(0);
    }

    /* ----------------------- tables ----------------------- */

    private boolean isTableStart(String[] raw, int i) {
        if (i + 1 >= raw.length) return false;
        String head = raw[i].trim();
        if (!head.startsWith("|") && !looksLikeTableRow(head)) return false;
        return TABLE_SEP.matcher(raw[i + 1]).matches();
    }

    private boolean looksLikeTableRow(String line) {
        // A row that doesn't open with `|` is fine if it contains at least one `|`.
        return line.indexOf('|') >= 0;
    }

    private int collectTableEnd(String[] raw, int start) {
        int i = start + 2; // header + separator already consumed
        while (i < raw.length) {
            String s = raw[i].trim();
            if (s.isEmpty()) break;
            if (!looksLikeTableRow(s)) break;
            i++;
        }
        return i;
    }

    private List<AttributedString> renderTable(String[] raw, int start, int end) {
        List<String[]> rows = new ArrayList<>();
        rows.add(splitCells(raw[start]));
        // Skip raw[start+1] (separator)
        for (int i = start + 2; i < end; i++) {
            rows.add(splitCells(raw[i]));
        }
        int cols = 0;
        for (String[] row : rows) cols = Math.max(cols, row.length);
        int[] widths = new int[cols];
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], displayWidth(row[c]));
            }
        }
        List<AttributedString> out = new ArrayList<>();
        out.add(borderLine(widths, '┌', '┬', '┐'));
        out.add(dataLine(rows.get(0), widths, /*header=*/true));
        out.add(borderLine(widths, '├', '┼', '┤'));
        for (int r = 1; r < rows.size(); r++) {
            out.add(dataLine(rows.get(r), widths, false));
        }
        out.add(borderLine(widths, '└', '┴', '┘'));
        return out;
    }

    private static String[] splitCells(String row) {
        String s = row.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private AttributedString borderLine(int[] widths, char left, char mid, char right) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (tableBorderStyle != null) sb.style(tableBorderStyle);
        sb.append(left);
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) sb.append(mid);
            sb.append("─".repeat(widths[c] + 2));
        }
        sb.append(right);
        return sb.toAttributedString();
    }

    private AttributedString dataLine(String[] row, int[] widths, boolean header) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        AttributedStyle border = tableBorderStyle == null ? AttributedStyle.DEFAULT : tableBorderStyle;
        for (int c = 0; c < widths.length; c++) {
            sb.style(border);
            sb.append(c == 0 ? "│ " : " │ ");
            sb.style(AttributedStyle.DEFAULT);
            String cell = c < row.length ? row[c] : "";
            AttributedStyle cellStyle = header
                    ? (headingStyle == null ? AttributedStyle.DEFAULT.bold() : headingStyle)
                    : AttributedStyle.DEFAULT;
            AttributedStringBuilder inner = new AttributedStringBuilder();
            inner.style(cellStyle);
            appendInline(inner, cell, cellStyle);
            sb.append(inner.toAttributedString());
            int pad = widths[c] - displayWidth(cell);
            if (pad > 0) sb.append(" ".repeat(pad));
        }
        sb.style(border);
        sb.append(" │");
        return sb.toAttributedString();
    }

    /**
     * Display-width approximation. Strips inline markers so the column
     * width matches what the user will actually see, not the raw cell
     * source ({@code **Bold**} renders 4 cols, not 8).
     */
    static int displayWidth(String cell) {
        String stripped = cell
                .replace("**", "")
                .replaceAll("(?<!\\\\)`", "")
                .replaceAll("(?<![*_])[*_](?![*_])", "");
        return stripped.length();
    }

    /* ----------------------- misc ----------------------- */

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) end--;
        return end == s.length() ? s : s.substring(0, end);
    }

    private static boolean isBlank(AttributedString s) {
        return s.length() == 0 || s.toString().isBlank();
    }

    private static AttributedString styled(String text, @Nullable AttributedStyle style) {
        if (style == null) return new AttributedString(text);
        return new AttributedStringBuilder().style(style).append(text).toAttributedString();
    }
}
