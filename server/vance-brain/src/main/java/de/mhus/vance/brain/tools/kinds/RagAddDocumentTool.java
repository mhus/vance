package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Add a Vance document (by id or path) to a RAG. Idempotent —
 * the {@code sourceRef} is set to the document id, so re-ingest
 * replaces the previous chunks for that document.
 */
@Component
@RequiredArgsConstructor
public class RagAddDocumentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("ragName"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("ragName", Map.of("type", "string",
                "description", "Target RAG name within the current project."));
        return p;
    }

    private final KindToolSupport support;
    private final RagService ragService;

    @Override public String name() { return "rag_add_document"; }
    @Override public String description() {
        return "Index a Vance document (by id or path) into a RAG. Uses the document id as the "
                + "RAG sourceRef so re-ingest replaces prior chunks of the same document — "
                + "idempotent on repeated calls.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("rag-bridge", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = ctx.projectId();
        if (projectId == null) throw new ToolException("RAG tools require a project scope");
        String ragName = KindToolSupport.requireString(params, "ragName");

        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        // Flush the buffer so we index the in-flight body, not stale disk.
        support.buffer().flush(ctx.processId(), doc.getId());
        DocumentDocument fresh = support.buffer().read(ctx.processId(), doc.getId());
        if (fresh == null) throw new ToolException("Document disappeared during indexing");

        RagDocument rag = ragService.findByName(ctx.tenantId(), projectId, ragName)
                .orElseThrow(() -> new ToolException("Unknown RAG '" + ragName
                        + "' in project '" + projectId + "'"));
        long replaced = ragService.removeBySource(rag.getId(), fresh.getId());
        RagService.IngestResult result = ragService.addText(
                rag.getId(), fresh.getId(), fresh.getInlineText(), null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rag", rag.getName());
        out.put("documentId", fresh.getId());
        out.put("documentPath", fresh.getPath());
        out.put("chunksAdded", result.chunksAdded());
        if (replaced > 0) out.put("chunksReplaced", replaced);
        return out;
    }
}
