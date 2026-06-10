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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchSearchExpertTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "Natural-language search query."),
                    "modality", Map.of(
                            "type", "string",
                            "description", "Result kind — web / image / video / pdf / news / "
                                    + "academic / book / encyclopedia / internal_doc."),
                    "instance", Map.of(
                            "type", "string",
                            "description", "Pin a specific endpoint id (e.g. 'wiki-de', "
                                    + "'serper-eu'). Run research_providers to discover ids."),
                    "domain", Map.of(
                            "type", "string",
                            "description", "Subject area hint (academic / news / encyclopedia / …)."),
                    "locale", Map.of(
                            "type", "string",
                            "description", "BCP-47 language tag (de, en, fr-CA …)."),
                    "dateFrom", Map.of(
                            "type", "string",
                            "description", "Restrict to results dated on or after (ISO yyyy-MM-dd)."),
                    "dateTo", Map.of(
                            "type", "string",
                            "description", "Restrict to results dated on or before (ISO yyyy-MM-dd)."),
                    "site", Map.of(
                            "type", "string",
                            "description", "Restrict to a host (e.g. 'arxiv.org')."),
                    "filetype", Map.of(
                            "type", "string",
                            "description", "Restrict to a file type (e.g. 'pdf', 'csv')."),
                    "num", Map.of(
                            "type", "integer",
                            "description", "Maximum results (1–10).")),
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
                + "don't understand a filter ignore it silently.";
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
                locale, pinnedInstance, expertParams);

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
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", hit.title());
            row.put("url", hit.url());
            if (!StringUtils.isBlank(hit.snippet())) row.put("snippet", hit.snippet());
            if (!StringUtils.isBlank(hit.source())) row.put("source", hit.source());
            if (hit.extras() != null) row.putAll(hit.extras());
            rows.add(row);
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
}
