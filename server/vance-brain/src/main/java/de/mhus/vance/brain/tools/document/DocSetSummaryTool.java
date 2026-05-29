package de.mhus.vance.brain.tools.document;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Set / update a document's summary field. Lightweight write that
 * does not touch tags or trigger the auto-summary scheduler. Useful
 * for binary content (images, PDFs) where the auto-summary scheduler
 * doesn't run on its own — the LLM brings the caption (e.g. from
 * the image-search result that produced the URL).
 *
 * <p>The summary surfaces in the slideshow caption hierarchy
 * (manifest captions → {@code doc.summary} → filename stem).
 */
@Component
@Slf4j
public class DocSetSummaryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "documentId", Map.of(
                            "type", "string",
                            "description", "Document id to update. Use the "
                                    + "id returned by `doc_import_url` / "
                                    + "`doc_find` / `doc_list`."),
                    "summary", Map.of(
                            "type", "string",
                            "description", "Summary text. Pass an empty "
                                    + "string to clear an existing summary.")),
            "required", List.of("documentId", "summary"));

    private final DocumentService documentService;

    public DocSetSummaryTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override public String name() { return "doc_set_summary"; }

    @Override
    public String description() {
        return "Set / update the document.summary field. The summary "
                + "is the human caption / one-line description; for "
                + "images and PDFs the LLM is expected to write this "
                + "directly because there's no auto-summary scheduler. "
                + "Slideshows fall back to it when no caption is set "
                + "in the manifest.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("write", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String documentId = paramString(params, "documentId");
        if (documentId == null) throw new ToolException("documentId is required");
        // `summary` is required but allowed to be blank — that's
        // how the caller clears the field.
        Object summaryRaw = params == null ? null : params.get("summary");
        if (summaryRaw == null || !(summaryRaw instanceof String summary)) {
            throw new ToolException("summary is required (pass an empty string to clear)");
        }

        DocumentDocument doc = documentService.findById(documentId)
                .orElseThrow(() -> new ToolException(
                        "Unknown document id '" + documentId + "'"));
        if (!ctx.tenantId().equals(doc.getTenantId())) {
            // Mirror the controller-side tenant guard so a guessed
            // id from a different tenant can't be touched.
            throw new ToolException("Unknown document id '" + documentId + "'");
        }

        documentService.setSummary(documentId, summary);
        log.info("DocSetSummaryTool tenant='{}' id='{}' cleared={}",
                ctx.tenantId(), documentId, summary.isBlank());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", documentId);
        out.put("path", doc.getPath());
        if (!summary.isBlank()) {
            out.put("summary", summary);
        } else {
            out.put("cleared", true);
        }
        return out;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
