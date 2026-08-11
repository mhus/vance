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
            "properties", buildProps(),
            "required", List.of("summary"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new java.util.LinkedHashMap<>(de.mhus.vance.brain.tools.kinds.KindToolSupport.documentSelectorPropertiesWithIdAlias());
        p.put("summary", Map.of(
                "type", "string",
                "description", "Summary text. Pass an empty "
                        + "string to clear an existing summary."));
        return p;
    }

    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final de.mhus.vance.brain.tools.kinds.KindToolSupport support;

    public DocSetSummaryTool(DocumentService documentService,
            de.mhus.vance.brain.permission.SecurityContextFactory contextFactory,
            de.mhus.vance.brain.tools.kinds.KindToolSupport support) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
        this.support = support;
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
        // `summary` is required but allowed to be blank — that's
        // how the caller clears the field.
        Object summaryRaw = params == null ? null : params.get("summary");
        if (summaryRaw == null || !(summaryRaw instanceof String summary)) {
            throw new ToolException("summary is required (pass an empty string to clear)");
        }

        // Standard doc selector (path | id, plus the legacy documentId alias).
        // Resolution, tenant scoping and the READ check live in loadDocument.
        DocumentDocument doc = support.loadDocument(
                de.mhus.vance.brain.tools.kinds.KindToolSupport.withIdAlias(params), ctx);
        String documentId = doc.getId();

        documentService.setSummary(documentId, summary,
                contextFactory.writeActor(ctx.tenantId(), ctx.userId(), doc.getPath()));
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
