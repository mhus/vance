package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
        p.put(
                "projectId",
                Map.of("type", "string", "description", "Optional project name. Defaults to the active project."));
        p.put(
                "pathPrefix",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Path-prefix scope. Omitted/blank → defaults to 'documents/' "
                                + "(excludes trash, kit config, chat attachments, engine scratch, "
                                + "and other system folders). Pass '*' to search the entire "
                                + "project (mounted docs under '_ext/' are excluded from a whole-"
                                + "project scan — use the Jaglan search tools for those; an explicit "
                                + "'_ext/…' prefix scans them deliberately). Pass any specific prefix "
                                + "(e.g. 'documents/notes/', '_vance/trash/') to narrow further or "
                                + "address a system folder explicitly."));
        p.put(
                "pattern",
                Map.of("type", "string", "description", "Java regex pattern. Use plain substrings for literal match."));
        p.put("caseInsensitive", Map.of("type", "boolean", "description", "Match case-insensitively. Default: false."));
        p.put(
                "contextBefore",
                Map.of("type", "integer", "description", "Number of lines to include before each match. Default: 0."));
        p.put(
                "contextAfter",
                Map.of("type", "integer", "description", "Number of lines to include after each match. Default: 0."));
        p.put(
                "outputMode",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("content", "files_with_matches"),
                        "description",
                        "`content` returns matching lines with line numbers; "
                                + "`files_with_matches` returns just the document paths. Default: content."));
        p.put(
                "limit",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Cap on total matches across all documents. Default: " + DEFAULT_LIMIT + ", max: " + MAX_LIMIT
                                + "."));
        p.put("maxScannedDocs", DocScanBudget.maxScannedDocsProperty());
        return p;
    }

    private final KindToolSupport support;

    @Override
    public String name() {
        return "doc_grep_path";
    }

    @Override
    public String description() {
        return "Search every inline document under a path prefix for lines matching a regex. "
                + "Returns either matching lines (with documentId, path, line number, optionally "
                + "context lines before/after) or just the list of files containing at least one "
                + "match. Age-encrypted documents are skipped — their bodies are ciphertext. "
                + "The scan is budgeted: at most " + DocScanBudget.DEFAULT_MAX_SCANNED_DOCS
                + " documents (raise via maxScannedDocs, hard cap " + DocScanBudget.MAX_SCANNED_DOCS_PARAM_CAP
                + ") and documents over " + (DocScanBudget.MAX_DOC_BYTES / (1024 * 1024)) + " MB are "
                + "skipped; a stopped scan reports truncated=true and a warning. Capped at "
                + MAX_LIMIT + " matches.";
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
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String patternStr = KindToolSupport.requireString(params, "pattern");
        String pathPrefix = de.mhus.vance.shared.document.DocumentService.resolveScope(
                KindToolSupport.paramString(params, "pathPrefix"));
        boolean ci = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "caseInsensitive"));
        Integer beforeParam = KindToolSupport.paramInt(params, "contextBefore");
        Integer afterParam = KindToolSupport.paramInt(params, "contextAfter");
        int before = beforeParam == null ? 0 : Math.max(0, beforeParam);
        int after = afterParam == null ? 0 : Math.max(0, afterParam);
        String outputMode = KindToolSupport.paramString(params, "outputMode");
        if (outputMode == null) outputMode = "content";
        Integer limitParam = KindToolSupport.paramInt(params, "limit");
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limitParam));

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage(), e);
        }
        // Shared wall-clock budget for the whole grep — the (untrusted) regex is
        // matched against every line of every scanned doc; a catastrophic-
        // backtracking pattern would otherwise pin the lane thread.
        long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;

        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        List<DocumentDocument> all = support.documentService().listByProject(ctx.tenantId(), project.getName());
        ScanScope scope = prepareScope(all, pathPrefix, params);

        if ("files_with_matches".equals(outputMode)) {
            List<Map<String, Object>> hits = new ArrayList<>();
            boolean truncated = false;
            for (DocumentDocument d : scope.candidates()) {
                if (!scope.budget().tryClaim(d.getSize())) {
                    if (scope.budget().exhausted()) {
                        truncated = true;
                        break;
                    }
                    continue; // oversized, skipped
                }
                // One fetch per document — this mode used to read every body
                // twice (null-check plus the actual scan).
                String body = support.readBody(d, ctx);
                if (containsMatch(body, pattern, deadline)) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("documentId", d.getId());
                    hit.put("path", d.getPath());
                    hits.add(hit);
                    if (hits.size() >= limit) {
                        truncated = true;
                        break;
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", project.getName());
            out.put("pattern", patternStr);
            out.put("scannedDocuments", scope.budget().scannedDocs());
            out.put("skippedOversized", scope.budget().skippedOversized());
            out.put("matchCount", hits.size());
            out.put("truncated", truncated);
            String warning = scope.budget().warning(scope.candidates().size(), scope.display());
            if (warning != null) out.put("warning", warning);
            out.put("matches", hits);
            return out;
        }

        // content mode
        List<Map<String, Object>> hits = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (DocumentDocument d : scope.candidates()) {
            if (!scope.budget().tryClaim(d.getSize())) {
                if (scope.budget().exhausted()) {
                    truncated = true;
                    break outer;
                }
                continue; // oversized, skipped
            }
            String[] lines = support.readBody(d, ctx).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!matchGuarded(pattern, lines[i], deadline)) continue;
                if (hits.size() >= limit) {
                    truncated = true;
                    break outer;
                }
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("documentId", d.getId());
                hit.put("path", d.getPath());
                hit.put("lineNumber", i + 1);
                hit.put("line", lines[i]);
                // Same context shape as doc_grep: a sibling list of
                // {lineNumber,line} excluding the match itself. Searching many
                // documents is exactly where a bare hit line is least useful,
                // yet this was the tool that couldn't produce context.
                if (before > 0 || after > 0) {
                    List<Map<String, Object>> context = new ArrayList<>();
                    int from = Math.max(0, i - before);
                    int to = Math.min(lines.length - 1, i + after);
                    for (int j = from; j <= to; j++) {
                        if (j == i) continue;
                        context.add(Map.of("lineNumber", j + 1, "line", lines[j]));
                    }
                    if (!context.isEmpty()) hit.put("context", context);
                }
                hits.add(hit);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("pattern", patternStr);
        out.put("scannedDocuments", scope.budget().scannedDocs());
        out.put("skippedOversized", scope.budget().skippedOversized());
        out.put("matchCount", hits.size());
        out.put("truncated", truncated);
        String warning = scope.budget().warning(scope.candidates().size(), scope.display());
        if (warning != null) out.put("warning", warning);
        out.put("matches", hits);
        return out;
    }

    /**
     * The scan set for one invocation: prefix- and age-filtered, path-sorted
     * (so early stops at a budget are deterministic and fair), with mounted
     * docs excluded from a whole-project ('*') scan — one mounted read can
     * be an external fetch, which is a different cost profile than a storage
     * fetch. An explicit {@code _ext/…} prefix keeps them, deliberately.
     */
    private ScanScope prepareScope(List<DocumentDocument> all, String pathPrefix, Map<String, Object> params) {
        boolean wholeProject = pathPrefix.isEmpty();
        List<DocumentDocument> candidates = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (!pathPrefix.isEmpty() && !d.getPath().startsWith(pathPrefix)) continue;
            if (AgeDocumentKind.isAgeEncrypted(d.getKind(), d.getMimeType())) continue;
            if (wholeProject && JaglanPaths.isMounted(d.getPath())) continue;
            candidates.add(d);
        }
        candidates.sort(java.util.Comparator.comparing(DocumentDocument::getPath));
        return new ScanScope(candidates, DocScanBudget.fromParams(params), wholeProject ? "*" : pathPrefix);
    }

    private record ScanScope(List<DocumentDocument> candidates, DocScanBudget budget, String display) {}

    private static final long REGEX_BUDGET_NANOS = 2_000_000_000L; // 2s per grep call

    private static boolean containsMatch(String text, Pattern pattern, long deadline) {
        // Split + scan instead of pattern.matcher(text).find() so a
        // multi-line dot-greedy regex doesn't accidentally swallow
        // the whole body.
        for (String line : text.split("\\R", -1)) {
            if (matchGuarded(pattern, line, deadline)) return true;
        }
        return false;
    }

    /** {@link RegexGuard#find} but mapping a budget overrun to a clean ToolException. */
    private static boolean matchGuarded(Pattern pattern, String line, long deadline) {
        try {
            return RegexGuard.find(pattern, line, deadline);
        } catch (RegexGuard.RegexBudgetExceeded e) {
            throw new ToolException(
                    "regex too slow — possible catastrophic backtracking; "
                            + "simplify the pattern (avoid nested quantifiers like (a+)+).",
                    e);
        }
    }
}
