package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lists all RAGs in the current project with their stats. */
@Component
@RequiredArgsConstructor
public class RagListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final RagService ragService;

    @Override
    public String name() {
        return "rag_list";
    }

    @Override
    public String description() {
        return "List the RAGs available in the current project — name, "
                + "description, chunk count, embedding model.";
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
        List<RagDocument> rags = ragService.listByProject(ctx.tenantId(), projectId);
        List<Map<String, Object>> rows = new ArrayList<>(rags.size());
        for (RagDocument r : rags) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.getName());
            row.put("description", r.getDescription());
            row.put("chunkCount", r.getChunkCount());
            row.put("embeddingProvider", r.getEmbeddingProvider());
            row.put("embeddingModel", r.getEmbeddingModel());
            row.put("embeddingDim", r.getEmbeddingDim());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rags", rows);
        out.put("count", rows.size());
        return out;
    }
}
