package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.rag.RagBackend.SearchHit;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Queries a named RAG with natural-language text and returns the
 * top-K most similar chunks. {@code score} is cosine similarity in
 * {@code [0, 1]} where higher is closer.
 */
@Component
@RequiredArgsConstructor
public class RagQueryTool implements Tool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "RAG name within the current project."),
                    "query", Map.of(
                            "type", "string",
                            "description", "Natural-language query."),
                    "topK", Map.of(
                            "type", "integer",
                            "description", "Number of hits to return (1–"
                                    + MAX_TOP_K + ", default " + DEFAULT_TOP_K + ").")),
            "required", List.of("name", "query"));

    private final RagService ragService;

    @Override
    public String name() {
        return "rag_query";
    }

    @Override
    public String description() {
        return "Search a project RAG with a natural-language query. Returns "
                + "the top-K most similar chunks with their content, score, "
                + "and source reference.";
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
        String query = stringOrThrow(params, "query");
        int topK = clampTopK(params == null ? null : params.get("topK"));

        RagDocument rag = ragService.findByName(ctx.tenantId(), projectId, name)
                .orElseThrow(() -> new ToolException("Unknown RAG '" + name
                        + "' in project '" + projectId + "'"));
        try {
            List<SearchHit> hits = ragService.query(rag.getId(), query, topK);
            List<Map<String, Object>> rows = new ArrayList<>(hits.size());
            for (SearchHit hit : hits) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("score", hit.score());
                row.put("sourceRef", hit.chunk().getSourceRef());
                row.put("position", hit.chunk().getPosition());
                row.put("content", hit.chunk().getContent());
                if (hit.chunk().getMetadata() != null && !hit.chunk().getMetadata().isEmpty()) {
                    row.put("metadata", hit.chunk().getMetadata());
                }
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rag", rag.getName());
            out.put("query", query);
            out.put("hits", rows);
            out.put("count", rows.size());
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("rag_query failed: " + e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static int clampTopK(Object raw) {
        int n = DEFAULT_TOP_K;
        if (raw instanceof Number num) n = num.intValue();
        if (n < 1) return 1;
        if (n > MAX_TOP_K) return MAX_TOP_K;
        return n;
    }
}
