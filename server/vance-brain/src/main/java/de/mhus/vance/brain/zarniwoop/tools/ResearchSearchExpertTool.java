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
 * Deferred EXPERT-tier counterpart to {@code research_search}. Exposes
 * the filter surface (instance pin, site, filetype, dateRange,
 * locale, domain) so the LLM can drive a precise lookup when the
 * normal-tier search isn't enough.
 *
 * <p>"instance" pins a specific endpoint id from
 * {@code research_providers}; when set, the dispatcher uses only that
 * instance — no fallback. Other filters land in
 * {@link SearchRequest#expertParams()}; protocols ignore the ones
 * they don't understand.
 *
 * <h2>Why there is a generic {@code params} object</h2>
 *
 * <p>The five named filters below are the ones every protocol might
 * plausibly share. A source, however, <b>declares its own</b> in
 * {@code capabilities.expertParams}, and {@code research_providers}
 * shows that list to the model — an Ode endpoint saying it understands
 * {@code originPlace} is the intended way to offer a source-specific
 * filter.
 *
 * <p>Without a generic channel that declaration is a dead letter: the
 * model is told the filter exists, sends it, and this tool drops it
 * because the copy list is closed. Announcing a parameter and then
 * discarding it is worse than not announcing it, because the failure is
 * silent — the search simply comes back unfiltered.
 *
 * <p>Keys travel unchanged. Which ones mean something is the source's
 * business; protocols are asked to ignore rather than refuse what they
 * do not know, and the named filters are written last so a stray
 * {@code params.site} cannot shadow the documented spelling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchSearchExpertTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.ofEntries(
                    Map.entry("query", Map.of(
                            "type", "string",
                            "description", "Natural-language search query.")),
                    Map.entry("modality", Map.of(
                            "type", "string",
                            "description", "Result kind — web / image / video / pdf / news / "
                                    + "academic / book / encyclopedia / internal_doc.")),
                    Map.entry("instance", Map.of(
                            "type", "string",
                            "description", "Pin a specific endpoint id (e.g. 'wiki-de', "
                                    + "'serper-eu'). Run research_providers to discover ids.")),
                    Map.entry("domain", Map.of(
                            "type", "string",
                            "description", "Subject area hint (academic / news / encyclopedia / …).")),
                    Map.entry("locale", Map.of(
                            "type", "string",
                            "description", "BCP-47 language tag (de, en, fr-CA …).")),
                    Map.entry("dateFrom", Map.of(
                            "type", "string",
                            "description", "Restrict to results dated on or after (ISO yyyy-MM-dd).")),
                    Map.entry("dateTo", Map.of(
                            "type", "string",
                            "description", "Restrict to results dated on or before (ISO yyyy-MM-dd).")),
                    Map.entry("site", Map.of(
                            "type", "string",
                            "description", "Restrict to a host (e.g. 'arxiv.org').")),
                    Map.entry("filetype", Map.of(
                            "type", "string",
                            "description", "Restrict to a file type (e.g. 'pdf', 'csv').")),
                    Map.entry("num", Map.of(
                            "type", "integer",
                            "description", "Maximum results (1–10).")),
                    Map.entry("facets", Map.of(
                            "type", "object",
                            "description", "Structured filter by an endpoint's declared "
                                    + "dimensions, e.g. {\"origin-place\": [\"m49:142\"]}. "
                                    + "Distinct from 'params': an endpoint that does not "
                                    + "declare a selected key is skipped rather than "
                                    + "ignoring it. Keys and values come from "
                                    + "research_providers.")),
                    Map.entry("params", Map.of(
                            "type", "object",
                            "description", "Endpoint-specific filters, by the exact names the "
                                    + "endpoint declares. Run research_providers first — an "
                                    + "endpoint lists the expert filters it understands, and "
                                    + "only those do anything. Values must be scalars."))),
            "required", List.of("query", "modality"));

    private final ZarniwoopService zarniwoopService;

    @Override
    public String name() {
        return "research_search_expert";
    }

    @Override
    public String description() {
        return "Expert-tier search with precise filter control. Use when "
                + "you need to pin a specific endpoint (e.g. 'wiki-de'), "
                + "restrict by site / filetype / date range, or steer "
                + "domain affinity. The 'instance' parameter overrides "
                + "the normal default/fallback cascade. Other filters "
                + "are forwarded to the protocol — protocols that "
                + "don't understand a filter ignore it silently. Use "
                + "'params' for endpoint-specific filters an endpoint "
                + "declares in research_providers. Hits "
                + "carry the same fields as research_search, including a "
                + "shortened 'body' where the source ships its own text.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Pin search endpoint, filter by date/site/filetype/locale.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null) {
            throw new ToolException("research_search_expert requires a tool invocation context");
        }
        if (params == null) {
            throw new ToolException("'query' and 'modality' are required");
        }
        Object qRaw = params.get("query");
        if (!(qRaw instanceof String query) || StringUtils.isBlank(query)) {
            throw new ToolException("'query' is required");
        }
        Object mRaw = params.get("modality");
        if (!(mRaw instanceof String modalityStr) || StringUtils.isBlank(modalityStr)) {
            throw new ToolException("'modality' is required");
        }
        SearchModality modality = ResearchSearchTool.parseModality(modalityStr);

        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("research tools require a project scope");
        }
        SearchScope scope = new SearchScope(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), ctx.userId());

        Object instanceRaw = params.get("instance");
        String pinnedInstance = instanceRaw instanceof String s && !StringUtils.isBlank(s)
                ? s.trim()
                : null;

        Map<String, Object> expertParams = new LinkedHashMap<>();
        copyDeclaredParams(params.get("params"), expertParams);
        copyIfString(params, "domain", expertParams);
        copyIfString(params, "site", expertParams);
        copyIfString(params, "filetype", expertParams);
        copyIfString(params, "dateFrom", expertParams);
        copyIfString(params, "dateTo", expertParams);

        java.util.Locale locale = null;
        Object localeRaw = params.get("locale");
        if (localeRaw instanceof String ls && !StringUtils.isBlank(ls)) {
            try {
                locale = java.util.Locale.forLanguageTag(ls.trim());
            } catch (RuntimeException e) {
                throw new ToolException("Invalid locale '" + ls + "': " + e.getMessage());
            }
        }

        int num = ResearchSearchTool.clampNum(params.get("num"));

        SearchRequest req = new SearchRequest(
                query, modality, SearchTier.EXPERT, num,
                locale, pinnedInstance, expertParams, ResearchSearchTool.facets(params));

        SearchResult result;
        try {
            result = zarniwoopService.search(req, scope, ctx);
        } catch (ZarniwoopException e) {
            throw new ToolException(e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", result.query());
        out.put("modality", result.modality().name().toLowerCase(Locale.ROOT));
        out.put("providerInstanceId", result.providerInstanceId());
        out.put("count", result.returnedCount());
        if (result.droppedCount() > 0) out.put("droppedCount", result.droppedCount());
        if (!StringUtils.isBlank(result.note())) out.put("note", result.note());
        if (!result.ok()) out.put("error", result.errorMessage());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            rows.add(SearchHitRows.shape(hit));
        }
        out.put("results", rows);
        return out;
    }

    private static void copyIfString(Map<String, Object> from, String key,
                                     Map<String, Object> to) {
        Object v = from.get(key);
        if (v instanceof String s && !StringUtils.isBlank(s)) {
            to.put(key, s.trim());
        }
    }

    /**
     * Copy the endpoint-specific {@code params} object into the expert
     * parameters.
     *
     * <p>Scalars only. A nested object or array would have to be serialised
     * into a query the far end never agreed on, and no declared filter needs
     * one — the shape a source asks for is a value, not a document. Anything
     * else is skipped with a warning rather than refused: a malformed filter
     * should cost the filter, not the search.
     */
    private static void copyDeclaredParams(@org.jspecify.annotations.Nullable Object raw,
                                           Map<String, Object> to) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key) || StringUtils.isBlank(key)) {
                continue;
            }
            Object value = e.getValue();
            switch (value) {
                case String s when !StringUtils.isBlank(s) -> to.put(key.trim(), s.trim());
                case Number n -> to.put(key.trim(), n);
                case Boolean b -> to.put(key.trim(), b);
                case null -> { }
                default -> log.warn(
                        "research_search_expert: dropping non-scalar expert parameter '{}' ({})",
                        key, value.getClass().getSimpleName());
            }
        }
    }
}
