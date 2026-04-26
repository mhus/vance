package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates a new RAG in the current project. The embedding model is
 * picked from the tenant's {@code ai.embedding.*} settings; its
 * vector dimension is probed once and stored on the RAG so later
 * ingests/queries stay consistent.
 */
@Component
@RequiredArgsConstructor
public class RagCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Stable RAG name, unique per project."),
                    "description", Map.of(
                            "type", "string",
                            "description", "Optional human-readable description."),
                    "chunkSize", Map.of(
                            "type", "integer",
                            "description", "Char-based chunk size. Default 1000."),
                    "chunkOverlap", Map.of(
                            "type", "integer",
                            "description", "Char overlap between chunks. Default 200.")),
            "required", List.of("name"));

    private final RagService ragService;

    @Override
    public String name() {
        return "rag_create";
    }

    @Override
    public String description() {
        return "Create a new RAG (retrieval index) in the current project. "
                + "The embedding model is configured tenant-wide; the RAG records "
                + "which model and vector dimension it uses.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = ctx.projectId();
        if (projectId == null) {
            throw new ToolException("RAG tools require a project scope");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }
        String description = params == null ? null
                : (params.get("description") instanceof String s ? s : null);
        int chunkSize = intOr(params, "chunkSize", 1000);
        int chunkOverlap = intOr(params, "chunkOverlap", 200);
        try {
            RagDocument rag = ragService.createRag(
                    ctx.tenantId(), projectId, name, /*title=*/ null,
                    description, chunkSize, chunkOverlap);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ragId", rag.getId());
            out.put("name", rag.getName());
            out.put("embeddingProvider", rag.getEmbeddingProvider());
            out.put("embeddingModel", rag.getEmbeddingModel());
            out.put("embeddingDim", rag.getEmbeddingDim());
            out.put("chunkSize", rag.getChunkSize());
            out.put("chunkOverlap", rag.getChunkOverlap());
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("rag_create failed: " + e.getMessage(), e);
        }
    }

    private static int intOr(Map<String, Object> params, String key, int fallback) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
