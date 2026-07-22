package de.mhus.vance.foot.markdown;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.StyleParser;
import de.mhus.vance.foot.ui.TerminalSanitizer;
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
     * Render {@code markdown} to a list of styled lines using an
     * effectively unbounded terminal width — tables keep their natural
     * width without per-cell wrapping. Equivalent to
     * {@link #render(String, int)} with {@link Integer#MAX_VALUE}.
     */
    public List<AttributedString> render(String markdown) {
        return render(markdown, Integer.MAX_VALUE);
    }

    /**
     * Render {@code markdown} to a list of styled lines, fitting tables
     * into {@code terminalWidth} columns by shrinking the widest column
     * until the total fits and wrapping cell content into the shrunk
     * width. Code fences, headings and prose are unaffected by
     * {@code terminalWidth} — prose wrap is governed by the separate
     * {@code vance.ui.markdown.wrap-width} budget.
     */
    public List<AttributedString> render(String markdown, int terminalWidth) {
        List<AttributedString> out = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return out;
        // The rendered content is server-/LLM-supplied and its ESC bytes
        // would survive AttributedStringBuilder.append into the terminal.
        // Strip control chars up front (keeping \n/\t) so only JLine's own
        // style escapes reach the screen (code-review F3).
        markdown = TerminalSanitizer.sanitizeContent(markdown);
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
                out.addAll(renderTable(raw, i, j, terminalWidth));
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

    /** Minimum content width per column we'll never shrink below. */
    private static final int MIN_COL_WIDTH = 6;

    private List<AttributedString> renderTable(String[] raw, int start, int end, int terminalWidth) {
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
        shrinkToFit(widths, terminalWidth);
        List<AttributedString> out = new ArrayList<>();
        out.add(borderLine(widths, '┌', '┬', '┐'));
        out.addAll(dataRow(rows.get(0), widths, /*header=*/true));
        out.add(borderLine(widths, '├', '┼', '┤'));
        for (int r = 1; r < rows.size(); r++) {
            out.addAll(dataRow(rows.get(r), widths, false));
        }
        out.add(borderLine(widths, '└', '┴', '┘'));
        return out;
    }

    /**
     * Iteratively shrink the widest column by one column until the
     * total rendered table width fits {@code terminalWidth}. A column
     * is never pushed below {@link #MIN_COL_WIDTH} — once every column
     * has hit the floor we give up and let the table overflow (better
     * to wrap to the next terminal row than to render a single char
     * per column).
     */
    private static void shrinkToFit(int[] widths, int terminalWidth) {
        if (terminalWidth <= 0 || terminalWidth == Integer.MAX_VALUE) return;
        int cols = widths.length;
        // Overhead per row: 1 (left border) + 2*cols (left/right padding
        // per cell) + (cols-1) (column dividers) + 1 (right border)
        // = 3*cols + 1, but the leading column also has 1 padding-left
        // which we already counted (the `c == 0 ? "│ " : " │ "` choice
        // changes which side the padding lands on, but the total stays
        // 3*cols + 1).
        int overhead = 3 * cols + 1;
        int maxContent = terminalWidth - overhead;
        if (maxContent < cols * MIN_COL_WIDTH) {
            maxContent = cols * MIN_COL_WIDTH;
        }
        int sum = 0;
        for (int w : widths) sum += w;
        while (sum > maxContent) {
            int widest = 0;
            for (int c = 1; c < cols; c++) {
                if (widths[c] > widths[widest]) widest = c;
            }
            if (widths[widest] <= MIN_COL_WIDTH) break;
            widths[widest]--;
            sum--;
        }
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

    /**
     * Render one logical table row to one-or-more physical rows. When a
     * cell's content exceeds its assigned column width, the content
     * wraps via {@link #wrap} into several display lines; the row's
     * physical height becomes the max wrapped-line count across all
     * cells. Cells with fewer lines are padded with blanks so the
     * borders stay aligned.
     */
    private List<AttributedString> dataRow(String[] row, int[] widths, boolean header) {
        int cols = widths.length;
        AttributedStyle cellStyle = header
                ? (headingStyle == null ? AttributedStyle.DEFAULT.bold() : headingStyle)
                : AttributedStyle.DEFAULT;
        List<List<AttributedString>> perCell = new ArrayList<>(cols);
        int height = 1;
        for (int c = 0; c < cols; c++) {
            String cell = c < row.length ? row[c] : "";
            AttributedStringBuilder inner = new AttributedStringBuilder();
            inner.style(cellStyle);
            appendInline(inner, cell, cellStyle);
            List<AttributedString> wrapped = wrap(inner.toAttributedString(), widths[c]);
            perCell.add(wrapped);
            if (wrapped.size() > height) height = wrapped.size();
        }
        AttributedStyle border = tableBorderStyle == null ? AttributedStyle.DEFAULT : tableBorderStyle;
        List<AttributedString> out = new ArrayList<>(height);
        for (int line = 0; line < height; line++) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            for (int c = 0; c < cols; c++) {
                sb.style(border);
                sb.append(c == 0 ? "│ " : " │ ");
                sb.style(AttributedStyle.DEFAULT);
                List<AttributedString> wrapped = perCell.get(c);
                AttributedString piece = line < wrapped.size()
                        ? wrapped.get(line)
                        : AttributedString.EMPTY;
                sb.append(piece);
                int pad = widths[c] - piece.length();
                if (pad > 0) sb.append(" ".repeat(pad));
            }
            sb.style(border);
            sb.append(" │");
            out.add(sb.toAttributedString());
        }
        return out;
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
