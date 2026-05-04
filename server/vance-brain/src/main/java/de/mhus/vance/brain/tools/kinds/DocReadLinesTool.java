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
 * Read a slice of a document by line range, prefixed with line
 * numbers — same shape as Claude Code's Read tool ({@code <n>\t<line>}).
 * Lets the LLM look at just the relevant chunk instead of pulling
 * the whole body.
 */
@Component
@RequiredArgsConstructor
public class DocReadLinesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("offset", Map.of("type", "integer",
                "description", "1-based line number to start from. Default: 1."));
        p.put("limit", Map.of("type", "integer",
                "description", "Number of lines to return. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_read_lines"; }
    @Override public String description() {
        return "Read a slice of an inline document by line range, prefixed with line numbers "
                + "in `<n>\\t<line>` format. Use `offset` (1-based) and `limit` to page through "
                + "large bodies without loading the whole thing into context.";
    }
    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        Integer offsetParam = KindToolSupport.paramInt(params, "offset");
        Integer limitParam = KindToolSupport.paramInt(params, "limit");
        int offset = offsetParam == null ? 1 : Math.max(1, offsetParam);
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limitParam));

        String[] lines = doc.getInlineText().split("\\R", -1);
        int total = lines.length;
        if (offset > total) {
            return Map.of("documentId", doc.getId(),
                    "totalLines", total,
                    "offset", offset,
                    "returnedLines", 0,
                    "content", "",
                    "truncated", false);
        }
        int end = Math.min(total, offset - 1 + limit);
        StringBuilder out = new StringBuilder();
        for (int i = offset - 1; i < end; i++) {
            out.append(i + 1).append('\t').append(lines[i]).append('\n');
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("path", doc.getPath());
        result.put("totalLines", total);
        result.put("offset", offset);
        result.put("returnedLines", end - (offset - 1));
        result.put("truncated", end < total);
        result.put("content", out.toString());
        return result;
    }
}
