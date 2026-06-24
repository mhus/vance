package de.mhus.vance.foot.tools;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StyleParser;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;

/**
 * Coloured unified-style line diff for {@code client_file_write} /
 * {@code client_file_edit} output. Computes an LCS-based line diff,
 * groups changes into hunks with N lines of context, and emits one
 * {@link org.jline.utils.AttributedString} per visible line:
 *
 * <ul>
 *   <li>added lines: {@code +} prefix, "add" style (default green bg)</li>
 *   <li>removed lines: {@code -} prefix, "remove" style (default red bg)</li>
 *   <li>context lines: leading space, "context" style</li>
 *   <li>hunk separator / file-edge markers: {@code ...}, "marker" style
 *       (default black bg)</li>
 * </ul>
 *
 * <p>A hard line cap ({@link FootConfig.ToolOutput#getDiffMaxLines})
 * stops the output if a full-file rewrite would otherwise scroll a
 * terminal off-screen; a trailing marker tells the user it was cut.
 *
 * <p>This is not the package-private helper of a single renderer — it
 * is reusable by anything that wants to display a coloured diff. The
 * one current caller is {@link ClientToolPrettyRenderer}.
 */
public final class FileDiffRenderer {

    private final ChatTerminal terminal;
    private final @Nullable AttributedStyle addStyle;
    private final @Nullable AttributedStyle removeStyle;
    private final @Nullable AttributedStyle contextStyle;
    private final @Nullable AttributedStyle markerStyle;
    private final int contextLines;
    private final int maxLines;

    public FileDiffRenderer(ChatTerminal terminal, FootConfig.ToolOutput cfg) {
        this.terminal = terminal;
        this.addStyle = StyleParser.parse(cfg.getDiffAdd());
        this.removeStyle = StyleParser.parse(cfg.getDiffRemove());
        this.contextStyle = StyleParser.parse(cfg.getDiffContext());
        this.markerStyle = StyleParser.parse(cfg.getDiffMarker());
        this.contextLines = Math.max(0, cfg.getDiffContextLines());
        this.maxLines = Math.max(1, cfg.getDiffMaxLines());
    }

    /**
     * Render the diff between {@code oldContent} and {@code newContent}
     * to the chat terminal. Either argument may be {@code null} (treated
     * as empty — useful for "new file" or "file deleted"). Emits nothing
     * when the contents are equal.
     */
    public void render(@Nullable String oldContent, @Nullable String newContent) {
        List<String> oldLines = splitLines(oldContent == null ? "" : oldContent);
        List<String> newLines = splitLines(newContent == null ? "" : newContent);
        List<DiffOp> ops = computeDiff(oldLines, newLines);
        if (ops.stream().noneMatch(o -> o.type != OpType.CONTEXT)) return;
        List<Hunk> hunks = groupHunks(ops, contextLines);
        emit(ops, hunks);
    }

    // ---------------------------------------------------------------------
    // Diff algorithm — LCS, classic O(n*m) table. Fine for the typical
    // file sizes a foot user edits (<<10k lines); for huge rewrites the
    // diffMaxLines cap kicks in on emit, not here.
    // ---------------------------------------------------------------------

    enum OpType { CONTEXT, ADD, REMOVE }

    static final class DiffOp {
        final OpType type;
        final String line;
        DiffOp(OpType type, String line) {
            this.type = type;
            this.line = line;
        }
    }

    static final class Hunk {
        final int startOp;
        final int endOp;
        Hunk(int startOp, int endOp) {
            this.startOp = startOp;
            this.endOp = endOp;
        }
    }

    static List<DiffOp> computeDiff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (a.get(i).equals(b.get(j))) {
                    lcs[i + 1][j + 1] = lcs[i][j] + 1;
                } else {
                    lcs[i + 1][j + 1] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        List<DiffOp> ops = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
                ops.add(new DiffOp(OpType.CONTEXT, a.get(i - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                ops.add(new DiffOp(OpType.ADD, b.get(j - 1)));
                j--;
            } else {
                ops.add(new DiffOp(OpType.REMOVE, a.get(i - 1)));
                i--;
            }
        }
        Collections.reverse(ops);
        return ops;
    }

    /**
     * Groups change ops into hunks: each hunk spans from {@code ctx}
     * lines before its first change to {@code ctx} lines after its last
     * change. Adjacent hunks merge when the gap between them is shorter
     * than the combined context (i.e. their context windows would
     * overlap) — produces the same hunk layout as {@code diff -U}.
     */
    static List<Hunk> groupHunks(List<DiffOp> ops, int ctx) {
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).type != OpType.CONTEXT) changes.add(i);
        }
        if (changes.isEmpty()) return List.of();
        List<Hunk> hunks = new ArrayList<>();
        int curStart = Math.max(0, changes.get(0) - ctx);
        int curEnd = Math.min(ops.size(), changes.get(0) + 1 + ctx);
        for (int idx = 1; idx < changes.size(); idx++) {
            int c = changes.get(idx);
            if (c - ctx <= curEnd) {
                curEnd = Math.min(ops.size(), c + 1 + ctx);
            } else {
                hunks.add(new Hunk(curStart, curEnd));
                curStart = Math.max(0, c - ctx);
                curEnd = Math.min(ops.size(), c + 1 + ctx);
            }
        }
        hunks.add(new Hunk(curStart, curEnd));
        return hunks;
    }

    private void emit(List<DiffOp> ops, List<Hunk> hunks) {
        int emitted = 0;
        for (int h = 0; h < hunks.size(); h++) {
            Hunk hunk = hunks.get(h);
            // Marker before the hunk when there's hidden content above:
            // either the first hunk doesn't start at 0, or it's a
            // between-hunks gap.
            if (hunk.startOp > 0) {
                if (emitMarker()) emitted++;
                if (emitted >= maxLines) { emitTruncationMarker(); return; }
            }
            for (int i = hunk.startOp; i < hunk.endOp; i++) {
                DiffOp op = ops.get(i);
                emitOp(op);
                emitted++;
                if (emitted >= maxLines && i < hunk.endOp - 1) {
                    emitTruncationMarker();
                    return;
                }
            }
            // Trailing edge marker only after the last hunk and only if
            // unshown content remains.
            if (h == hunks.size() - 1 && hunk.endOp < ops.size()) {
                if (emitMarker()) emitted++;
            }
        }
    }

    private void emitOp(DiffOp op) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        switch (op.type) {
            case ADD -> {
                if (addStyle != null) sb.style(addStyle);
                sb.append("+").append(op.line);
            }
            case REMOVE -> {
                if (removeStyle != null) sb.style(removeStyle);
                sb.append("-").append(op.line);
            }
            case CONTEXT -> {
                if (contextStyle != null) sb.style(contextStyle);
                sb.append(" ").append(op.line);
            }
        }
        terminal.printlnStyled(Verbosity.INFO, sb.toAttributedString());
    }

    private boolean emitMarker() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (markerStyle != null) sb.style(markerStyle);
        sb.append("...");
        terminal.printlnStyled(Verbosity.INFO, sb.toAttributedString());
        return true;
    }

    private void emitTruncationMarker() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (markerStyle != null) sb.style(markerStyle);
        sb.append("... (diff truncated at ").append(String.valueOf(maxLines)).append(" lines)");
        terminal.printlnStyled(Verbosity.INFO, sb.toAttributedString());
    }

    /**
     * Splits on \n; a trailing newline does NOT produce an extra empty
     * "line" entry. This matches "how a user thinks about lines" — a
     * file ending in \n has the same line count as one that doesn't.
     */
    static List<String> splitLines(String s) {
        if (s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                int end = (i > 0 && s.charAt(i - 1) == '\r') ? i - 1 : i;
                out.add(s.substring(start, end));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }
}
