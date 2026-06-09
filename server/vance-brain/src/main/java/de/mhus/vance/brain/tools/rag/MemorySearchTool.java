package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.ProjectRagService;
import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.shared.rag.RagBackend.SearchHit;
import de.mhus.vance.shared.rag.RagDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Search the current project's default memory (the auto-indexed
 * {@code _documents} RAG) with a natural-language query. Companion to
 * the passive {@link de.mhus.vance.brain.rag.RagAutoInjectService}
 * which injects memory hits based on the current user turn — this
 * tool lets the engine pose its <em>own</em> follow-up queries with
 * its own {@code topK} and filtering.
 *
 * <p>Different from {@link RagQueryTool} on purpose: {@code rag_query}
 * targets <em>named</em> RAGs (custom collections the user created via
 * {@code rag_create}). {@code memory_search} hard-targets the
 * project-default RAG so the engine doesn't have to know the
 * underscore-prefixed system name and can't accidentally search a
 * wrong collection.
 */
@Component
@RequiredArgsConstructor
public class MemorySearchTool implements Tool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    /** Headroom factor for the post-filter — when the caller passes a
     *  {@code pathPrefix} we pull this many extra candidates from the
     *  backend so the filter has material to keep the result count
     *  close to the requested {@code topK}. */
    private static final int FILTER_HEADROOM = 5;

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "Natural-language query for the project memory."));
        props.put("topK", Map.of(
                "type", "integer",
                "description", "Max number of hits to return (1–" + MAX_TOP_K
                        + ", default " + DEFAULT_TOP_K + ")."));
        props.put("minScore", Map.of(
                "type", "number",
                "description", "Minimum cosine similarity score in [0, 1]. "
                        + "Default 0 (no threshold). Use 0.6+ for strict matches."));
        props.put("pathPrefix", Map.of(
                "type", "string",
                "description", "Optional document-path prefix to filter results "
                        + "(e.g. 'documents/meeting-notes/'). Matched against the "
                        + "chunk's source document path."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("query"));
    }

    private final RagService ragService;
    private final ProjectRagService projectRagService;

    @Override public String name() { return "memory_search"; }

    @Override public String description() {
        return "Search the current project's default memory (auto-indexed "
                + "documents) with a natural-language query. Returns chunks "
                + "ranked by similarity, with content, score, path and "
                + "position. Optional minScore + pathPrefix filters.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "memory"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("memory_search requires a project scope");
        }
        String query = stringOrThrow(params, "query");
        int topK = clampInt(params == null ? null : params.get("topK"),
                DEFAULT_TOP_K, 1, MAX_TOP_K);
        double minScore = clampDouble(params == null ? null : params.get("minScore"),
                0.0, 0.0, 1.0);
        String pathPrefix = optionalString(params, "pathPrefix");

        Optional<RagDocument> ragOpt = projectRagService.findDefaultRag(
                ctx.tenantId(), ctx.projectId());
        if (ragOpt.isEmpty()) {
            // The default RAG is auto-provisioned on first project
            // bring, but for projects created before that became the
            // default — or projects with rag.project.enabled=false —
            // it may legitimately not exist. Surface a friendly
            // result, not an exception, so the engine can fall back
            // to web_search / asking the user.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("hits", List.of());
            out.put("count", 0);
            out.put("note", "Project has no default memory (rag.project.enabled may be false, "
                    + "or the project was created before the project-RAG default landed).");
            return out;
        }

        // Pull extra candidates when post-filtering by pathPrefix so
        // the returned list still has a chance of reaching topK after
        // the filter. Without pathPrefix the backend already orders
        // by score and the cut at topK is exact.
        int backendTopK = pathPrefix == null ? topK : Math.min(MAX_TOP_K * FILTER_HEADROOM, topK * FILTER_HEADROOM);

        List<SearchHit> raw;
        try {
            raw = ragService.query(ragOpt.get().getId(), query, backendTopK);
        } catch (RuntimeException e) {
            throw new ToolException("memory_search failed: " + e.getMessage(), e);
        }

        List<Map<String, Object>> rows = new ArrayList<>(topK);
        for (SearchHit hit : raw) {
            if (hit.score() < minScore) {
                // RagBackend returns hits sorted by descending score —
                // once we drop below the threshold the remainder is
                // also below, so we could break here. We don't, only
                // because some backends might not strictly sort
                // (cosine ties); the cost of iterating a small list
                // is negligible.
                continue;
            }
            String path = pathFromMetadata(hit);
            if (pathPrefix != null && (path == null || !path.startsWith(pathPrefix))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("score", hit.score());
            if (path != null) row.put("path", path);
            row.put("sourceRef", hit.chunk().getSourceRef());
            row.put("position", hit.chunk().getPosition());
            row.put("content", hit.chunk().getContent());
            rows.add(row);
            if (rows.size() >= topK) break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("hits", rows);
        out.put("count", rows.size());
        if (pathPrefix != null) out.put("pathPrefix", pathPrefix);
        if (minScore > 0) out.put("minScore", minScore);
        return out;
    }

    /**
     * Lifts the source document's project-relative path from the chunk
     * metadata. The {@link de.mhus.vance.brain.rag.ProjectRagIndexer}
     * always sets {@code metadata.path} to {@code doc.getPath()} when
     * it indexes a document; chunks from other indexing pathways
     * (manual {@code rag_add_text}) may not have one — those don't
     * pass the {@code pathPrefix} filter, which is correct.
     */
    private static @org.jspecify.annotations.Nullable String pathFromMetadata(SearchHit hit) {
        Map<String, Object> meta = hit.chunk().getMetadata();
        if (meta == null) return null;
        Object raw = meta.get("path");
        return raw instanceof String s ? s : null;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static @org.jspecify.annotations.Nullable String optionalString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static int clampInt(Object raw, int defaultValue, int min, int max) {
        int n = defaultValue;
        if (raw instanceof Number num) {
            n = num.intValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        if (n < min) return min;
        if (n > max) return max;
        return n;
    }

    private static double clampDouble(Object raw, double defaultValue, double min, double max) {
        double n = defaultValue;
        if (raw instanceof Number num) {
            n = num.doubleValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try { n = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        if (n < min) return min;
        if (n > max) return max;
        return n;
    }
}
