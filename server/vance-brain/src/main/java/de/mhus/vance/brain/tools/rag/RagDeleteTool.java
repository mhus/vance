package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes a RAG entirely — catalog entry plus every chunk. The
 * action is destructive and not reversible from the LLM side, so
 * the tool stays secondary and the LLM should call it explicitly
 * after confirming with the user.
 */
@Component
@RequiredArgsConstructor
public class RagDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "RAG name within the current project.")),
            "required", List.of("name"));

    private final RagService ragService;

    @Override
    public String name() {
        return "rag_delete";
    }

    @Override
    public String description() {
        return "Delete a RAG and all its chunks from the current project. "
                + "Destructive — confirm with the user before calling.";
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
        Optional<RagDocument> found = ragService.findByName(ctx.tenantId(), projectId, name);
        if (found.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("deleted", false);
            return out;
        }
        boolean ok = ragService.deleteRag(found.get().getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("deleted", ok);
        return out;
    }
}
