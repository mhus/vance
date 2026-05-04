package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
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
 * Search every document under a path prefix in a project for lines
 * matching a regex pattern. The Vance equivalent of running
 * {@code grep -rn} over a folder.
 *
 * <p>Two output modes — {@code content} returns matching lines with
 * line numbers (capped), {@code files_with_matches} returns just the
 * document paths that had at least one hit. Default is
 * {@code content} but capped at {@link #DEFAULT_LIMIT} matches so
 * the LLM context doesn't explode.
 */
@Component
@RequiredArgsConstructor
public class DocGrepPathTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("pattern"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("projectId", Map.of("type", "string",
                "description", "Optional project name. Defaults to the active project."));
        p.put("pathPrefix", Map.of("type", "string",
                "description", "Restrict the search to documents whose path starts with this "
                        + "prefix (e.g. 'notes/', 'recipes/'). Empty/omitted = entire project."));
        p.put("pattern", Map.of("type", "string",
                "description", "Java regex pattern. Use plain substrings for literal match."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match case-insensitively. Default: false."));
        p.put("outputMode", Map.of("type", "string", "enum", List.of("content", "files_with_matches"),
                "description", "`content` returns matching lines with line numbers; "
                        + "`files_with_matches` returns just the document paths. Default: content."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on total matches across all documents. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_grep_path"; }
    @Override public String description() {
        return "Search every inline document under a path prefix for lines matching a regex. "
                + "Returns either matching lines (with documentId, path, line number) or just the "
                + "list of files containing at least one match. Capped at " + MAX_LIMIT + " matches.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("text-search", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String patternStr = KindToolSupport.requireString(params, "pattern");
        String pathPrefix = KindToolSupport.paramString(params, "pathPrefix");
        boolean ci = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "caseInsensitive"));
        String outputMode = KindToolSupport.paramString(params, "outputMode");
        if (outputMode == null) outputMode = "content";
        Integer limitParam = KindToolSupport.paramInt(params, "limit");
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limitParam));

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage());
        }

        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        List<DocumentDocument> all = support.documentService()
                .listByProject(ctx.tenantId(), project.getName());

        if ("files_with_matches".equals(outputMode)) {
            List<Map<String, Object>> hits = new ArrayList<>();
            int scanned = 0;
            for (DocumentDocument d : all) {
                if (pathPrefix != null && !d.getPath().startsWith(pathPrefix)) continue;
                if (d.getInlineText() == null) continue;
                scanned++;
                if (containsMatch(d.getInlineText(), pattern)) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("documentId", d.getId());
                    hit.put("path", d.getPath());
                    hits.add(hit);
                    if (hits.size() >= limit) break;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", project.getName());
            out.put("pattern", patternStr);
            out.put("scannedDocuments", scanned);
            out.put("matchCount", hits.size());
            out.put("matches", hits);
            return out;
        }

        // content mode
        List<Map<String, Object>> hits = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        outer:
        for (DocumentDocument d : all) {
            if (pathPrefix != null && !d.getPath().startsWith(pathPrefix)) continue;
            if (d.getInlineText() == null) continue;
            scanned++;
            String[] lines = d.getInlineText().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!pattern.matcher(lines[i]).find()) continue;
                if (hits.size() >= limit) { truncated = true; break outer; }
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("documentId", d.getId());
                hit.put("path", d.getPath());
                hit.put("lineNumber", i + 1);
                hit.put("line", lines[i]);
                hits.add(hit);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("pattern", patternStr);
        out.put("scannedDocuments", scanned);
        out.put("matchCount", hits.size());
        out.put("truncated", truncated);
        out.put("matches", hits);
        return out;
    }

    private static boolean containsMatch(String text, Pattern pattern) {
        // Split + scan instead of pattern.matcher(text).find() so a
        // multi-line dot-greedy regex doesn't accidentally swallow
        // the whole body.
        for (String line : text.split("\\R", -1)) {
            if (pattern.matcher(line).find()) return true;
        }
        return false;
    }
}
