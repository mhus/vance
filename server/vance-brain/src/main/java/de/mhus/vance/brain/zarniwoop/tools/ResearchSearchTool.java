package de.mhus.vance.brain.zarniwoop.tools;

import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.brain.zarniwoop.ZarniwoopService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * LLM-facing entry point for normal-tier searches. Accepts
 * {@code query}, optional {@code modality} (default {@code web}) and
 * optional {@code num} (1–10, default 5). Delegates to
 * {@link ZarniwoopService} and reshapes the {@link SearchResult} into
 * a plain JSON-able map the dispatcher serialises into the LLM
 * tool-result channel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchSearchTool implements Tool {

    static final int DEFAULT_NUM = 5;
    static final int MAX_NUM = 10;

    private static final List<String> MODALITY_ENUM = buildModalityEnum();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "Natural-language search query."),
                    "modality", Map.of(
                            "type", "string",
                            "enum", MODALITY_ENUM,
                            "description", "Result kind — defaults to 'web'. "
                                    + "Run research_providers to see which modalities "
                                    + "have a provider instance configured in this project."),
                    "num", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum results to return (1–" + MAX_NUM
                                            + ", default " + DEFAULT_NUM + ").")),
            "required", List.of("query"));

    private final ZarniwoopService zarniwoopService;

    @Override
    public String name() {
        return "research_search";
    }

    @Override
    public String description() {
        return "Search the web (and other configured sources) for "
                + "information about a topic. Returns ranked hits with "
                + "title, URL and snippet. Default modality is 'web' — "
                + "set 'modality' to image / video / pdf / news / "
                + "academic / book / encyclopedia / internal_doc when "
                + "you specifically want that kind of result. For "
                + "richer control over filters and provider selection, "
                + "see research_search_expert.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Provider down = retry once; no results = broaden query, switch modality; rate-limit = wait.";
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("research", "search", "integration");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null) {
            throw new ToolException("research_search requires a tool invocation context");
        }
        if (params == null) {
            throw new ToolException("'query' is required");
        }
        Object queryRaw = params.get("query");
        if (!(queryRaw instanceof String query) || StringUtils.isBlank(query)) {
            throw new ToolException("'query' is required");
        }
        SearchModality modality = parseModality(params.get("modality"));
        int num = clampNum(params.get("num"));

        SearchScope scope = new SearchScope(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), ctx.userId());
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ToolException("research tools require a project scope");
        }

        SearchRequest req = new SearchRequest(
                query, modality, SearchTier.NORMAL, num,
                null, null, Map.of());

        SearchResult result;
        try {
            result = zarniwoopService.search(req, scope, ctx);
        } catch (ZarniwoopException e) {
            throw new ToolException(e.getMessage());
        }
        return shape(result);
    }

    static SearchModality parseModality(Object raw) {
        if (raw == null) return SearchModality.WEB;
        if (!(raw instanceof String s) || StringUtils.isBlank(s)) {
            return SearchModality.WEB;
        }
        String upper = s.trim().toUpperCase(Locale.ROOT);
        for (SearchModality m : SearchModality.values()) {
            if (m.name().equals(upper)) return m;
        }
        throw new ToolException("Unknown modality '" + s + "'. "
                + "Allowed: " + MODALITY_ENUM);
    }

    static int clampNum(Object raw) {
        int n = DEFAULT_NUM;
        if (raw instanceof Number number) {
            n = number.intValue();
        } else if (raw instanceof String s && !StringUtils.isBlank(s)) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                n = DEFAULT_NUM;
            }
        }
        if (n < 1) return 1;
        if (n > MAX_NUM) return MAX_NUM;
        return n;
    }

    private static Map<String, Object> shape(SearchResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", result.query());
        out.put("modality", result.modality().name().toLowerCase(Locale.ROOT));
        out.put("providerInstanceId", result.providerInstanceId());
        out.put("count", result.returnedCount());
        if (result.droppedCount() > 0) out.put("droppedCount", result.droppedCount());
        if (!StringUtils.isBlank(result.note())) out.put("note", result.note());
        if (!result.ok()) out.put("error", result.errorMessage());

        List<Map<String, Object>> hits = new ArrayList<>(result.hits().size());
        for (SearchHit hit : result.hits()) {
            hits.add(shapeHit(hit));
        }
        out.put("results", hits);
        return out;
    }

    private static Map<String, Object> shapeHit(SearchHit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", hit.title());
        row.put("url", hit.url());
        if (!StringUtils.isBlank(hit.snippet())) row.put("snippet", hit.snippet());
        if (!StringUtils.isBlank(hit.source())) row.put("source", hit.source());
        if (hit.extras() != null && !hit.extras().isEmpty()) {
            // Inline well-known extras directly; never wrap them in a sub-map
            // so the LLM sees them as first-class fields per modality.
            row.putAll(hit.extras());
        }
        return row;
    }

    private static List<String> buildModalityEnum() {
        List<String> out = new ArrayList<>();
        for (SearchModality m : SearchModality.values()) {
            out.add(m.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }
}
