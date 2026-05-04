package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Search a single document for lines matching a regex pattern,
 * returning line numbers and the matching text. Optional context
 * lines (before/after) like ripgrep's {@code -B}/{@code -A}.
 */
@Component
@RequiredArgsConstructor
public class DocGrepTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("pattern"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("pattern", Map.of("type", "string",
                "description", "Java regular expression. Use plain substrings for literal match."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match case-insensitively. Default: false."));
        p.put("contextBefore", Map.of("type", "integer",
                "description", "Number of lines to include before each match. Default: 0."));
        p.put("contextAfter", Map.of("type", "integer",
                "description", "Number of lines to include after each match. Default: 0."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on the number of matches returned. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_grep"; }
    @Override public String description() {
        return "Search one document for lines matching a regex pattern. Returns matching lines "
                + "with their 1-based line numbers, optionally with context lines before/after. "
                + "Pattern is Java regex; literal substring is fine.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-search", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        String patternStr = KindToolSupport.requireString(params, "pattern");
        boolean ci = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "caseInsensitive"));
        Integer beforeParam = KindToolSupport.paramInt(params, "contextBefore");
        Integer afterParam = KindToolSupport.paramInt(params, "contextAfter");
        Integer limitParam = KindToolSupport.paramInt(params, "limit");
        int before = beforeParam == null ? 0 : Math.max(0, beforeParam);
        int after = afterParam == null ? 0 : Math.max(0, afterParam);
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limitParam));

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage());
        }

        String[] lines = doc.getInlineText().split("\\R", -1);
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < lines.length; i++) {
            if (!pattern.matcher(lines[i]).find()) continue;
            if (matches.size() >= limit) { truncated = true; break; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNumber", i + 1);
            m.put("line", lines[i]);
            if (before > 0 || after > 0) {
                List<Map<String, Object>> context = new ArrayList<>();
                int from = Math.max(0, i - before);
                int to = Math.min(lines.length - 1, i + after);
                for (int j = from; j <= to; j++) {
                    if (j == i) continue;
                    context.add(Map.of("lineNumber", j + 1, "line", lines[j]));
                }
                if (!context.isEmpty()) m.put("context", context);
            }
            matches.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        out.put("pattern", patternStr);
        out.put("matchCount", matches.size());
        out.put("truncated", truncated);
        out.put("matches", matches);
        return out;
    }
}
