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
 * Adds raw text to a RAG. If a {@code sourceRef} is given and the
 * RAG already has chunks for that source, those are removed first
 * — the call is then idempotent on re-ingest of the same source.
 * Without a {@code sourceRef} the chunks just append.
 */
@Component
@RequiredArgsConstructor
public class RagAddTextTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "RAG name within the current project."),
                    "text", Map.of(
                            "type", "string",
                            "description", "Text to chunk + embed + store."),
                    "sourceRef", Map.of(
                            "type", "string",
                            "description", "Optional logical source id (file, URL, tag). "
                                    + "If set, prior chunks with the same sourceRef are replaced.")),
            "required", List.of("name", "text"));

    private final RagService ragService;

    @Override
    public String name() {
        return "rag_add_text";
    }

    @Override
    public String description() {
        return "Add text to a RAG: chunk + embed + store. With an optional "
                + "sourceRef, re-ingest replaces prior chunks of the same source.";
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
        String name = stringOrThrow(params, "name");
        String text = stringOrThrow(params, "text");
        String sourceRef = params != null && params.get("sourceRef") instanceof String s
                && !s.isBlank() ? s : null;

        RagDocument rag = ragService.findByName(ctx.tenantId(), projectId, name)
                .orElseThrow(() -> new ToolException("Unknown RAG '" + name
                        + "' in project '" + projectId + "'"));
        try {
            long replaced = 0;
            if (sourceRef != null) {
                replaced = ragService.removeBySource(rag.getId(), sourceRef);
            }
            RagService.IngestResult result = ragService.addText(
                    rag.getId(), sourceRef, text, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rag", rag.getName());
            out.put("sourceRef", sourceRef);
            out.put("chunksAdded", result.chunksAdded());
            if (replaced > 0) out.put("chunksReplaced", replaced);
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("rag_add_text failed: " + e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
