package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        int fromLine = KindToolSupport.requireInt(params, "fromLine");
        int toLine = KindToolSupport.requireInt(params, "toLine");
        String newContent = KindToolSupport.requireRawString(params, "newContent");

        String[] lines = doc.getInlineText().split("\\R", -1);
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
        if (toLine > total) toLine = total; // resilient: clamp instead of error

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

    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
