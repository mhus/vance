package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.brain.tools.document.AgeDocumentGuard;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Count lines and characters for one document (selector) or across every
 * document under a path prefix — the doc-side twin of {@code file_count}.
 * An optional regex narrows the line count to matches (wc-style stats),
 * with {@code chars} aggregating the matched line text.
 *
 * <p>The prefix scan is a class-3 cost (one storage fetch per document),
 * so it runs under the shared {@link DocScanBudget}: capped
 * {@code maxScannedDocs}, oversized documents skipped, mounted docs
 * excluded from a whole-project ('*') scan, deterministic path order, and
 * {@code truncated} + {@code warning} when the budget stops early. The
 * single-document mode needs none of that — one bounded fetch.
 */
@Component
@RequiredArgsConstructor
public class DocCountTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put(
                "pathPrefix",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Path-prefix scope for counting across many documents. "
                                + "Omitted/blank → defaults to 'documents/'. Pass '*' to count the entire "
                                + "project (mounted docs under '_ext/' are excluded — use an explicit "
                                + "'_ext/…' prefix for those). Ignored when a single document is selected "
                                + "via path/id."));
        p.put(
                "pattern",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional regex. When set, 'lines' counts only matching lines "
                                + "and 'chars' aggregates the matched line text."));
        p.put(
                "caseInsensitive",
                Map.of(
                        "type", "boolean",
                        "description", "Match the regex case-insensitively. Default: false."));
        p.put("maxScannedDocs", DocScanBudget.maxScannedDocsProperty());
        return p;
    }

    private final KindToolSupport support;

    @Override
    public String name() {
        return "doc_count";
    }

    @Override
    public String description() {
        return "Count lines and characters for a single document (path/id) or across every "
                + "document under a path prefix. Optional regex narrows the count to matching "
                + "lines (wc-style stats). The prefix scan is budgeted — at most "
                + DocScanBudget.DEFAULT_MAX_SCANNED_DOCS
                + " documents per call (raise via maxScannedDocs), oversized documents skipped; "
                + "a stopped scan reports truncated=true and a warning.";
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
        String patternStr = KindToolSupport.paramString(params, "pattern");
        boolean ci = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "caseInsensitive"));
        Pattern pattern;
        try {
            pattern = patternStr == null ? null : Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage(), e);
        }

        boolean single = KindToolSupport.paramString(params, "path") != null
                || KindToolSupport.paramString(params, "id") != null;
        if (single) {
            return countSingle(params, ctx, pattern, patternStr);
        }
        return countScan(params, ctx, pattern, patternStr);
    }

    private Map<String, Object> countSingle(
            Map<String, Object> params, ToolInvocationContext ctx, Pattern pattern, String patternStr) {
        DocumentDocument doc = support.loadDocument(params, ctx);
        AgeDocumentGuard.requireReadable(doc);
        String body = support.readBody(doc, ctx);
        Counts counts = Counts.count(body, pattern);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.getId());
        out.put("path", doc.getPath());
        if (patternStr != null) out.put("pattern", patternStr);
        out.put("lines", pattern == null ? counts.lines() : counts.matchingLines());
        if (pattern != null) out.put("totalLines", counts.lines());
        out.put("chars", pattern == null ? counts.chars() : counts.matchedChars());
        out.put("bytes", doc.getSize());
        return out;
    }

    private Map<String, Object> countScan(
            Map<String, Object> params, ToolInvocationContext ctx, Pattern pattern, String patternStr) {
        String pathPrefix = DocumentService.resolveScope(KindToolSupport.paramString(params, "pathPrefix"));
        boolean wholeProject = pathPrefix.isEmpty();
        DocScanBudget budget = DocScanBudget.fromParams(params);

        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        List<DocumentDocument> all = support.documentService().listByProject(ctx.tenantId(), project.getName());

        List<DocumentDocument> candidates = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (!pathPrefix.isEmpty() && !d.getPath().startsWith(pathPrefix)) continue;
            if (AgeDocumentKind.isAgeEncrypted(d.getKind(), d.getMimeType())) continue;
            if (wholeProject && JaglanPaths.isMounted(d.getPath())) continue;
            candidates.add(d);
        }
        candidates.sort(Comparator.comparing(DocumentDocument::getPath));

        long totalLines = 0;
        long totalMatchingLines = 0;
        long totalChars = 0;
        long matchedChars = 0;
        boolean truncated = false;
        for (DocumentDocument d : candidates) {
            if (!budget.tryClaim(d.getSize())) {
                if (budget.exhausted()) {
                    truncated = true;
                    break;
                }
                continue; // oversized, skipped
            }
            Counts counts = Counts.count(support.readBody(d, ctx), pattern);
            totalLines += counts.lines();
            totalMatchingLines += counts.matchingLines();
            totalChars += counts.chars();
            matchedChars += counts.matchedChars();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("pathPrefix", wholeProject ? "*" : pathPrefix);
        if (patternStr != null) out.put("pattern", patternStr);
        out.put("scannedDocuments", budget.scannedDocs());
        out.put("skippedOversized", budget.skippedOversized());
        out.put("lines", pattern == null ? totalLines : totalMatchingLines);
        if (pattern != null) out.put("totalLines", totalLines);
        out.put("chars", pattern == null ? totalChars : matchedChars);
        out.put("truncated", truncated);
        String warning = budget.warning(candidates.size(), wholeProject ? "*" : pathPrefix);
        if (warning != null) out.put("warning", warning);
        return out;
    }

    /**
     * Line semantics mirror {@code doc_read_lines} / {@code doc_grep}: split at
     * line terminators (a trailing newline yields a final empty line), chars =
     * body length, and with a pattern only matching lines count — with
     * {@code matchedChars} aggregating the matched line text.
     */
    private record Counts(long lines, long matchingLines, long chars, long matchedChars) {

        static Counts count(String body, Pattern pattern) {
            String[] lines = body.split("\\R", -1);
            if (pattern == null) {
                return new Counts(lines.length, 0, body.length(), 0);
            }
            long matchingLines = 0;
            long matchedChars = 0;
            for (String line : lines) {
                if (pattern.matcher(line).find()) {
                    matchingLines++;
                    matchedChars += line.length();
                }
            }
            return new Counts(lines.length, matchingLines, body.length(), matchedChars);
        }
    }
}
