package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.documents.summary.DocumentSummaryDriver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Return a 1-3-sentence summary plus topical tags for a project
 * document. Cheap when the summary is already cached on the doc
 * (set by the auto-summary scheduler the first time the doc is
 * indexed); on a cache-miss the tool synchronously runs the same
 * Jeltz-backed pipeline the scheduler uses and writes the summary
 * back to the doc before returning.
 *
 * <p>Used primarily by Vogon-strategy phase workers: each phase's
 * draft is persisted under {@code _vogon-drafts/<process>/<phase>.md}
 * and listed in the auto-injected {@code <phases>} block of the
 * next phase's workerInput. Workers call {@code doc_summary(path)}
 * to get a quick recap of "what the previous phase produced",
 * before deciding whether they need the full text via
 * {@code doc_read}. Keeps the workerInput prompt-budget small
 * even when prior phases produced multi-page drafts.
 *
 * <p>Read-only tool. Tagged accordingly so it remains primary in
 * the EXPLORING / PLANNING modes that strip write/executive tools.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocSummaryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Document path inside the active "
                                    + "project (e.g. '_vogon-drafts/<process>"
                                    + "/research-sources.md' or "
                                    + "'essay/outline.md').")),
            "required", List.of("path"));

    private final DocumentService documentService;
    private final ProjectService projectService;
    /**
     * Lazy via {@link ObjectProvider} — the driver pulls in
     * RecipeResolver + ThinkEngineService + LaneScheduler, which would
     * otherwise close a bean cycle through the tool-dispatch path.
     */
    private final ObjectProvider<DocumentSummaryDriver> summaryDriverProvider;

    @Override
    public String name() {
        return "doc_summary";
    }

    @Override
    public String description() {
        return "Return a 1-3-sentence summary + 3-8 topical tags for a "
                + "project document. Reads from cache when the auto-summary "
                + "scheduler has already indexed the doc; otherwise "
                + "synchronously generates the summary via a Jeltz call "
                + "(~1-3s) and writes it back. Use this for a quick recap "
                + "before deciding whether the full content via doc_read "
                + "is needed.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only", "document");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("doc_summary requires a tenant scope");
        }
        if (ctx.projectId() == null) {
            throw new ToolException("doc_summary requires a project scope");
        }
        String path = stringOrThrow(params, "path");

        DocumentDocument doc = documentService.findByPath(
                        ctx.tenantId(), ctx.projectId(), path)
                .orElseThrow(() -> new ToolException(
                        "Document '" + path + "' not found in project '"
                                + ctx.projectId() + "'"));

        // Cached path — return immediately.
        if (doc.getSummary() != null && !doc.getSummary().isBlank()) {
            return buildResult(doc, /*source*/ "cached");
        }

        // Lazy-generate. The driver loads its own system session,
        // spawns a Jeltz process, drives synchronously, and writes
        // doc.summary + doc.tags before returning.
        ProjectDocument project = projectService.findByTenantAndName(
                        ctx.tenantId(), ctx.projectId())
                .orElseThrow(() -> new ToolException(
                        "Project '" + ctx.projectId() + "' not found in tenant '"
                                + ctx.tenantId() + "'"));
        try {
            summaryDriverProvider.getObject().run(project, doc);
        } catch (RuntimeException e) {
            log.warn("doc_summary lazy-generation failed for tenant='{}' project='{}' path='{}': {}",
                    ctx.tenantId(), ctx.projectId(), path, e.toString());
            throw new ToolException(
                    "Failed to generate summary for '" + path + "': " + e.getMessage(), e);
        }

        DocumentDocument refreshed = documentService.findById(doc.getId())
                .orElse(doc);
        return buildResult(refreshed, /*source*/ "generated");
    }

    private static Map<String, Object> buildResult(DocumentDocument doc, String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", doc.getPath());
        out.put("summary", doc.getSummary() == null ? "" : doc.getSummary());
        out.put("tags", doc.getTags() == null ? List.of() : doc.getTags());
        out.put("source", source);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }
}
