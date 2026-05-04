package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bulk-index every inline document under a path prefix into a RAG.
 * Each document's id is used as its sourceRef so the operation is
 * idempotent on repeated runs over the same prefix.
 */
@Component
@RequiredArgsConstructor
public class RagAddPathTool implements Tool {

    private static final int MAX_DOCUMENTS = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "ragName", Map.of("type", "string",
                            "description", "Target RAG name within the project."),
                    "pathPrefix", Map.of("type", "string",
                            "description", "Index every inline document whose path starts with this. "
                                    + "Empty/omitted = entire project (use with care).")),
            "required", List.of("ragName"));

    private final KindToolSupport support;
    private final RagService ragService;

    @Override public String name() { return "rag_add_path"; }
    @Override public String description() {
        return "Bulk-index every inline document under `pathPrefix` into the named RAG. Each "
                + "document is indexed with its id as sourceRef, so a later re-run replaces just "
                + "those chunks (idempotent). Trash documents are skipped. Caps at "
                + MAX_DOCUMENTS + " documents per call.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("rag-bridge", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String ragName = KindToolSupport.requireString(params, "ragName");
        String pathPrefix = KindToolSupport.paramString(params, "pathPrefix");
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        RagDocument rag = ragService.findByName(ctx.tenantId(), project.getName(), ragName)
                .orElseThrow(() -> new ToolException("Unknown RAG '" + ragName
                        + "' in project '" + project.getName() + "'"));

        List<DocumentDocument> all = support.documentService()
                .listByProject(ctx.tenantId(), project.getName());
        List<Map<String, Object>> indexed = new ArrayList<>();
        int skipped = 0;
        long totalChunksAdded = 0;
        long totalChunksReplaced = 0;
        boolean truncated = false;

        for (DocumentDocument d : all) {
            if (DocumentService.isTrash(d.getPath())) { skipped++; continue; }
            if (pathPrefix != null && !d.getPath().startsWith(pathPrefix)) continue;
            if (d.getInlineText() == null) { skipped++; continue; }
            if (indexed.size() >= MAX_DOCUMENTS) { truncated = true; break; }
            try {
                long replaced = ragService.removeBySource(rag.getId(), d.getId());
                RagService.IngestResult result = ragService.addText(
                        rag.getId(), d.getId(), d.getInlineText(), null);
                totalChunksAdded += result.chunksAdded();
                totalChunksReplaced += replaced;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("documentId", d.getId());
                entry.put("path", d.getPath());
                entry.put("chunksAdded", result.chunksAdded());
                if (replaced > 0) entry.put("chunksReplaced", replaced);
                indexed.add(entry);
            } catch (RuntimeException e) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("documentId", d.getId());
                entry.put("path", d.getPath());
                entry.put("error", e.getMessage());
                indexed.add(entry);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rag", rag.getName());
        out.put("projectId", project.getName());
        out.put("indexedCount", indexed.size());
        out.put("skippedCount", skipped);
        out.put("totalChunksAdded", totalChunksAdded);
        if (totalChunksReplaced > 0) out.put("totalChunksReplaced", totalChunksReplaced);
        out.put("truncated", truncated);
        out.put("documents", indexed);
        return out;
    }
}
