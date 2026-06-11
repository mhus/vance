package de.mhus.vance.brain.zarniwoop.tools;

import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.brain.zarniwoop.ZarniwoopResearchService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.DroppedHit;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchScope;
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
 * LLM-facing entry point for the curated research pipeline. Single
 * required argument: {@code question}. Goes through the plan →
 * execute → evaluate phases in
 * {@link ZarniwoopResearchService} and reshapes the
 * {@link RankedHitSet} into a JSON-able map.
 *
 * <p>Slower than {@code research_search} (the LLM runs once for
 * planning and once for evaluation); use only when the user wants a
 * scored, filtered, multi-source corpus rather than a raw search
 * result list. The tool returns the corpus — not a written answer.
 * The caller composes prose from the ranked hits themselves.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchInvestigateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "question", Map.of(
                            "type", "string",
                            "description",
                                    "The research question in natural language. The plan-recipe "
                                            + "may rewrite it into search queries internally — pass "
                                            + "the user's wording verbatim.")),
            "required", List.of("question"));

    private final ZarniwoopResearchService researchService;

    @Override
    public String name() {
        return "research_investigate";
    }

    @Override
    public String description() {
        return "Run a curated multi-source research pass for a "
                + "question. Slower than research_search — it plans the "
                + "searches, runs them in parallel across the project's "
                + "configured providers, then scores and filters the "
                + "hits with a relevance-evaluation pass. Returns a "
                + "ranked corpus (top hits + dropped hits with reasons) "
                + "— NOT a written report. Compose your reply from the "
                + "ranked hits yourself. Use this when the user asks "
                + "for 'research', 'find me the best sources', 'what do "
                + "we know about X' — anything where the caller wants "
                + "curation, not raw search results.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null) {
            throw new ToolException("research_investigate requires a tool invocation context");
        }
        if (params == null) {
            throw new ToolException("'question' is required");
        }
        Object raw = params.get("question");
        if (!(raw instanceof String question) || StringUtils.isBlank(question)) {
            throw new ToolException("'question' is required");
        }
        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("research tools require a project scope");
        }
        SearchScope scope = new SearchScope(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), ctx.userId());

        RankedHitSet result;
        try {
            result = researchService.investigate(question, scope, ctx);
        } catch (ZarniwoopException e) {
            throw new ToolException(e.getMessage());
        }
        return shape(result);
    }

    private static Map<String, Object> shape(RankedHitSet result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", result.question());
        out.put("count", result.keptHits().size());
        out.put("droppedCount", result.droppedHits().size());
        out.put("instancesUsed", new ArrayList<>(result.instancesUsed()));
        if (!result.sourceAffinity().isEmpty()) {
            out.put("sourceAffinity", result.sourceAffinity());
        }

        List<Map<String, Object>> hits = new ArrayList<>(result.keptHits().size());
        for (RankedHit hit : result.keptHits()) {
            hits.add(shapeKept(hit));
        }
        out.put("results", hits);

        if (!result.droppedHits().isEmpty()) {
            List<Map<String, Object>> dropped = new ArrayList<>(result.droppedHits().size());
            for (DroppedHit d : result.droppedHits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", d.title());
                row.put("url", d.url());
                row.put("modality", d.modality().name().toLowerCase(Locale.ROOT));
                row.put("providerInstanceId", d.providerInstanceId());
                if (!StringUtils.isBlank(d.dropReason())) {
                    row.put("dropReason", d.dropReason());
                }
                dropped.add(row);
            }
            out.put("dropped", dropped);
        }

        if (!result.gaps().isEmpty()) {
            out.put("gaps", result.gaps());
        }
        return out;
    }

    private static Map<String, Object> shapeKept(RankedHit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", hit.title());
        row.put("url", hit.url());
        row.put("finalScore", round(hit.finalScore()));
        row.put("relevanceScore", round(hit.relevanceScore()));
        row.put("sourceAffinity", round(hit.sourceAffinityApplied()));
        row.put("modality", hit.modality().name().toLowerCase(Locale.ROOT));
        row.put("providerInstanceId", hit.providerInstanceId());
        if (!StringUtils.isBlank(hit.snippet())) row.put("snippet", hit.snippet());
        if (!StringUtils.isBlank(hit.relevanceNote())) {
            row.put("relevanceNote", hit.relevanceNote());
        }
        if (hit.extras() != null && !hit.extras().isEmpty()) {
            row.putAll(hit.extras());
        }
        return row;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
