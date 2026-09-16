package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.document.AgeDocumentGuard;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Returns the first {@code head} and / or last {@code tail} lines of a
 * document — the doc-side twin of {@code file_head_tail}. Either or both
 * may be specified; at least one is required so the tool always returns a
 * bounded slice rather than the full body. Rows carry their 1-based line
 * number so a follow-up {@code doc_replace_lines} can address them.
 */
@Component
@RequiredArgsConstructor
public class DocHeadTailTool implements Tool {

    private static final int MAX_LINES = 5_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put(
                "head",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Lines from the top. 0 / omitted = none. Capped at " + MAX_LINES + "."));
        p.put("tail", Map.of("type", "integer", "description", "Lines from the bottom. Capped at " + MAX_LINES + "."));
        return p;
    }

    private final KindToolSupport support;

    @Override
    public String name() {
        return "doc_head_tail";
    }

    @Override
    public String description() {
        return "Return the first N lines (head) and / or last N lines (tail) of a document. "
                + "At least one of head / tail must be > 0. Lines are 1-based; rows carry "
                + "lineNumber so doc_replace_lines or doc_read_lines can address them again.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("text-search", "eddie", "read-only");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("knowledge", "documents");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        AgeDocumentGuard.requireReadable(doc);
        int head = clampLines(KindToolSupport.paramInt(params, "head"));
        int tail = clampLines(KindToolSupport.paramInt(params, "tail"));
        if (head == 0 && tail == 0) {
            throw new ToolException("At least one of 'head' or 'tail' must be > 0");
        }

        String[] lines = support.readBody(doc, ctx).split("\\R", -1);
        int total = lines.length;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("totalLines", total);
        if (head > 0) {
            int n = Math.min(head, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", i + 1, "line", lines[i]));
            }
            out.put("head", rows);
        }
        if (tail > 0) {
            int n = Math.min(tail, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            int start = total - n;
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", start + i + 1, "line", lines[start + i]));
            }
            out.put("tail", rows);
        }
        return out;
    }

    private static int clampLines(Integer raw) {
        if (raw == null) return 0;
        return Math.min(MAX_LINES, Math.max(0, raw));
    }
}
