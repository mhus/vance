package de.mhus.vance.brain.zarniwoop.tools;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Multi-modality aggregator — runs WEB / IMAGE / VIDEO / PDF in
 * parallel through {@link ZarniwoopService} and returns one bucketed
 * result. Successor to the legacy {@code rich_search} tool, same
 * shape: {@code {query, text, images, videos, pdfs}}.
 *
 * <p>Sub-limits are deliberately not user-tunable in v1:
 * text=4, images=4, videos=2, pdfs=2. Adjusting later goes through
 * settings ({@code research.rich.<modality>.num}) — not addressed
 * here to keep the surface small until the telemetry says it's worth
 * the dial.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchRichTool implements Tool {

    private static final int N_TEXT = 4;
    private static final int N_IMAGES = 4;
    private static final int N_VIDEOS = 2;
    private static final int N_PDFS = 2;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Natural-language topic — the same string the user "
                                            + "typed, e.g. 'Lissabon', 'Quantum computing intro'. "
                                            + "The tool splits it across web/image/video/pdf "
                                            + "internally.")),
            "required", List.of("query"));

    private final ZarniwoopService zarniwoopService;

    @Override
    public String name() {
        return "research_rich";
    }

    @Override
    public String description() {
        return "Search the web and return a mixed result set — text "
                + "snippets, images, videos, and PDF documents in one "
                + "call. Use when the user asks 'zeig mir was zu X', "
                + "'gib mir einen Eindruck zu Y' — anything that wants "
                + "a rounded view. Each bucket comes with its own "
                + "validation: images and PDFs are HEAD-probed, videos "
                + "are oEmbed-checked. For text-only deep research, "
                + "call research_search directly instead.";
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
            throw new ToolException("research_rich requires a tool invocation context");
        }
        Object qRaw = params == null ? null : params.get("query");
        if (!(qRaw instanceof String query) || StringUtils.isBlank(query)) {
            throw new ToolException("'query' is required");
        }
        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("research tools require a project scope");
        }

        SearchScope scope = new SearchScope(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), ctx.userId());

        // Parallel fan-out over the four modalities. Local pool so the
        // threads die with the call — no shared executor lifecycle.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CompletableFuture<SearchResult> text = fan(pool,
                    () -> zarniwoopService.search(
                            req(query, SearchModality.WEB, N_TEXT), scope, ctx));
            CompletableFuture<SearchResult> images = fan(pool,
                    () -> zarniwoopService.search(
                            req(query, SearchModality.IMAGE, N_IMAGES), scope, ctx));
            CompletableFuture<SearchResult> videos = fan(pool,
                    () -> zarniwoopService.search(
                            req(query, SearchModality.VIDEO, N_VIDEOS), scope, ctx));
            CompletableFuture<SearchResult> pdfs = fan(pool,
                    () -> zarniwoopService.search(
                            req(query, SearchModality.PDF, N_PDFS), scope, ctx));
            CompletableFuture.allOf(text, images, videos, pdfs).join();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("text", bucket(text.getNow(null)));
            out.put("images", bucket(images.getNow(null)));
            out.put("videos", bucket(videos.getNow(null)));
            out.put("pdfs", bucket(pdfs.getNow(null)));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static SearchRequest req(String query, SearchModality modality, int num) {
        return new SearchRequest(query, modality, SearchTier.NORMAL, num,
                null, null, Map.of());
    }

    private CompletableFuture<SearchResult> fan(
            ExecutorService pool, Supplier<SearchResult> call) {
        return CompletableFuture.supplyAsync(call, pool).exceptionally(t -> {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            log.warn("research_rich: sub-search failed: {}", cause.toString());
            return null;
        });
    }

    private static Map<String, Object> bucket(SearchResult result) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (result == null) {
            b.put("results", List.of());
            b.put("count", 0);
            b.put("error", "sub-search failed");
            return b;
        }
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
        b.put("modality", result.modality().name().toLowerCase(Locale.ROOT));
        b.put("providerInstanceId", result.providerInstanceId());
        b.put("results", rows);
        b.put("count", result.returnedCount());
        if (result.droppedCount() > 0) b.put("droppedCount", result.droppedCount());
        if (!StringUtils.isBlank(result.note())) b.put("note", result.note());
        if (!result.ok()) b.put("error", result.errorMessage());
        return b;
    }
}
