package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Replace a contiguous range of lines with new content. Useful when
 * the LLM knows the line range from a previous {@code doc_grep} or
 * {@code doc_read_lines} call and wants a clean line-aware patch
 * without depending on uniqueness of an old_string snippet.
 *
 * <p>{@code fromLine} and {@code toLine} are 1-based and inclusive.
 * Set {@code toLine = fromLine - 1} to <em>insert</em> before
 * {@code fromLine} without replacing anything (e.g. fromLine=10,
 * toLine=9 inserts above line 10).
 *
 * <p><b>Line numbers can go stale.</b> On a live document (auto-saving
 * editor, managed blocks that rewrite themselves) the content — and thus
 * the line numbers — may shift between the read that produced the range
 * and this call. Two guards protect against silently patching the wrong
 * lines: an out-of-range {@code toLine} errors (never clamps), and the
 * optional {@code expectedFirstLine}/{@code expectedLastLine} anchors are
 * verified before patching. For structured, frequently-rewritten kinds
 * (e.g. workpages) prefer {@link DocEditTool doc_edit}'s content match.
 */
@Component
@RequiredArgsConstructor
public class DocReplaceLinesTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("fromLine", "toLine", "newContent"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("fromLine", Map.of("type", "integer",
                "description", "1-based line number where replacement starts (inclusive)."));
        p.put("toLine", Map.of("type", "integer",
                "description", "1-based line number where replacement ends (inclusive). "
                        + "Set toLine = fromLine - 1 to insert above fromLine without replacing."));
        p.put("newContent", Map.of("type", "string",
                "description", "Replacement text. Trailing newlines are normalised."));
        p.put("expectedFirstLine", Map.of("type", "string",
                "description", "Optional guard: the expected text of the FIRST line in the "
                        + "range (line `fromLine`). If it doesn't match the document, the call "
                        + "errors instead of patching the wrong lines — line numbers can go "
                        + "stale when a document changes between your read and this write. "
                        + "Compared whitespace-insensitively. Strongly recommended."));
        p.put("expectedLastLine", Map.of("type", "string",
                "description", "Optional guard: expected text of the LAST line in the range "
                        + "(line `toLine`). Same purpose as expectedFirstLine; ignored for a "
                        + "pure insert (toLine = fromLine - 1)."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_replace_lines"; }
    @Override public String description() {
        return "Replace lines [fromLine, toLine] (1-based, inclusive) of an inline document with "
                + "newContent. Use this when you know the line range — e.g. from a doc_grep or "
                + "doc_read_lines result — and want a precise line-level patch without the uniqueness "
                + "constraints of doc_edit.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("text-edit", "eddie", "write", "document"); }
    @Override public Set<String> prakLabels() { return Set.of("knowledge", "documents"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        int fromLine = KindToolSupport.requireInt(params, "fromLine");
        int toLine = KindToolSupport.requireInt(params, "toLine");
        String newContent = KindToolSupport.requireRawString(params, "newContent");
        String expectedFirst = KindToolSupport.paramString(params, "expectedFirstLine");
        String expectedLast = KindToolSupport.paramString(params, "expectedLastLine");

        String[] lines = support.readBody(doc, ctx).split("\\R", -1);
        int total = lines.length;
        if (fromLine < 1) throw new ToolException("fromLine must be >= 1");
        if (fromLine > total + 1) {
            throw new ToolException("fromLine " + fromLine + " exceeds document length "
                    + total + " (max insert position is " + (total + 1) + ")");
        }
        boolean insertOnly = toLine == fromLine - 1;
        if (!insertOnly && toLine < fromLine) {
            throw new ToolException("toLine " + toLine + " must be >= fromLine " + fromLine
                    + " (or fromLine - 1 for pure insert)");
        }
        // Härtung (1): fail loud instead of silently clamping. An out-of-range
        // toLine almost always means the line numbers are stale (the document
        // changed since the read). Clamping would then patch the wrong range.
        if (toLine > total) {
            throw new ToolException("toLine " + toLine + " exceeds document length " + total
                    + " — the line numbers are likely stale (the document changed since you read"
                    + " it). Re-read with doc_read_lines, or use doc_edit with an exact snippet.");
        }
        // Härtung (2): verify the range anchors before patching. A mismatch means
        // the line numbers point at the wrong content — error instead of clobbering.
        if (expectedFirst != null && !expectedFirst.isEmpty()) {
            if (fromLine - 1 >= total) {
                throw new ToolException("expectedFirstLine given but line " + fromLine
                        + " is past the document end (" + total + " lines) — stale line numbers."
                        + " Re-read with doc_read_lines, or use doc_edit.");
            }
            requireAnchor("expectedFirstLine", fromLine, lines[fromLine - 1], expectedFirst);
        }
        if (expectedLast != null && !expectedLast.isEmpty() && !insertOnly) {
            requireAnchor("expectedLastLine", toLine, lines[toLine - 1], expectedLast);
        }

        StringBuilder out = new StringBuilder();
        // Lines before the replacement window (1..fromLine-1)
        for (int i = 0; i < fromLine - 1; i++) {
            out.append(lines[i]).append('\n');
        }
        // The replacement
        if (!newContent.isEmpty()) {
            out.append(newContent);
            if (!newContent.endsWith("\n")) out.append('\n');
        }
        // Lines after the replacement window (toLine+1..end)
        int afterStart = insertOnly ? fromLine - 1 : toLine;
        for (int i = afterStart; i < total; i++) {
            out.append(lines[i]);
            if (i < total - 1) out.append('\n');
        }
        // Preserve trailing newline if the original had one — split
        // with limit=-1 leaves an empty trailing element in that case,
        // so the loop above already accounts for it (via the inner
        // newline). For explicit clarity though, keep it simple.

        support.writeBody(doc, out.toString(), ctx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("path", doc.getPath());
        result.put("fromLine", fromLine);
        result.put("toLine", insertOnly ? fromLine - 1 : toLine);
        result.put("inserted", insertOnly);
        result.put("newLineCount", countLines(newContent));
        return result;
    }

    /** Verify a range anchor: the actual line must equal the expected text
     *  (whitespace-insensitive). Throws with both values on mismatch so the
     *  LLM can see the drift and re-read or fall back to doc_edit. */
    private static void requireAnchor(String param, int line, String actual, String expected) {
        if (actual.trim().equals(expected.trim())) return;
        throw new ToolException(param + " mismatch at line " + line + ": document has \""
                + cap(actual) + "\" but you expected \"" + cap(expected) + "\". The line numbers"
                + " are likely stale — re-read with doc_read_lines, or use doc_edit.");
    }

    /** Truncate a line for readable error messages. */
    private static String cap(String s) {
        String t = s.length() > 80 ? s.substring(0, 80) + "…" : s;
        return t.replace("\n", "\\n");
    }

    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
