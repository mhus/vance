package de.mhus.vance.brain.tools.web;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mixed-content search — calls {@code web_search},
 * {@code image_search}, {@code video_search}, and {@code pdf_search}
 * in parallel and returns a bucketed result. Used when the user
 * wants a rounded view of a topic ("zeig mir was zu X", "was findest
 * du zu Y") rather than a specific format. Each downstream tool
 * keeps its own validation; this tool just routes and merges.
 *
 * <p>Bucket shape per type: {@code { results: [...], count: N,
 * dropped_count?: M, error?: msg }}. The top level carries the
 * original query so the engine can echo it back in its reply.
 *
 * <p>For text-only deep research, prefer {@link WebSearchTool}
 * directly — rich_search pays four Serper calls plus three
 * validators, which is wasteful when the user only needs sources.
 *
 * <p>Sub-limits are deliberate, not user-tunable in v1:
 * text=4, images=4, videos=2, pdfs=2 (= ~12 items total).
 * If a use case appears where the mix should shift we'll add
 * an {@code include} parameter later.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RichSearchTool implements Tool {

    static final int DEFAULT_TEXT = 4;
    static final int DEFAULT_IMAGES = 4;
    static final int DEFAULT_VIDEOS = 2;
    static final int DEFAULT_PDFS = 2;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Natural-language topic — the same string the "
                                            + "user typed, e.g. 'Lissabon', "
                                            + "'Quantum computing intro'. The tool "
                                            + "splits it across web/image/video/pdf "
                                            + "search internally.")),
            "required", List.of("query"));

    private final WebSearchTool webSearch;
    private final ImageSearchTool imageSearch;
    private final VideoSearchTool videoSearch;
    private final PdfSearchTool pdfSearch;

    @Override
    public String name() {
        return "rich_search";
    }

    @Override
    public String description() {
        return "Search the web and return a mixed result set — text "
                + "snippets, images, YouTube videos, and PDF documents "
                + "in one call. Use this when the user asks 'zeig mir "
                + "was zu X', 'was findest du zu Y', 'gib mir einen "
                + "Eindruck zu Z' — anything that wants a rounded view. "
                + "Each bucket comes with its own validation: images and "
                + "PDFs are HEAD-probed, videos are oEmbed-checked. The "
                + "result is bucketed by media type — render images as "
                + "![alt](url), videos via the embedFence string, PDFs "
                + "and text hits as plain [Title](url) markdown. For "
                + "text-only deep research where you want more than 4 "
                + "sources, call web_search directly instead.";
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
        String query = params == null ? null : (String) params.get("query");
        if (query == null || query.isBlank()) {
            throw new ToolException("'query' is required");
        }

        // Parallel fan-out across the four downstream tools. A fresh
        // pool per call keeps thread state local to the request and
        // avoids leaking on shutdown — total budget is bounded by
        // each downstream tool's own timeout (Serper 15s + validators
        // ~5s).
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CompletableFuture<Map<String, Object>> textFuture =
                    fanOut(pool, "web_search", () -> webSearch.invoke(
                            Map.of("query", query, "num", DEFAULT_TEXT), ctx));
            CompletableFuture<Map<String, Object>> imagesFuture =
                    fanOut(pool, "image_search", () -> imageSearch.invoke(
                            Map.of("query", query, "num", DEFAULT_IMAGES), ctx));
            CompletableFuture<Map<String, Object>> videosFuture =
                    fanOut(pool, "video_search", () -> videoSearch.invoke(
                            Map.of("query", query, "num", DEFAULT_VIDEOS), ctx));
            CompletableFuture<Map<String, Object>> pdfsFuture =
                    fanOut(pool, "pdf_search", () -> pdfSearch.invoke(
                            Map.of("query", query, "num", DEFAULT_PDFS), ctx));

            CompletableFuture.allOf(textFuture, imagesFuture, videosFuture, pdfsFuture).join();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("text", toBucket(textFuture.getNow(null)));
            out.put("images", toBucket(imagesFuture.getNow(null)));
            out.put("videos", toBucket(videosFuture.getNow(null)));
            out.put("pdfs", toBucket(pdfsFuture.getNow(null)));
            log.info("RichSearchTool query='{}' text={} images={} videos={} pdfs={}",
                    truncate(query, 80),
                    bucketCount(textFuture),
                    bucketCount(imagesFuture),
                    bucketCount(videosFuture),
                    bucketCount(pdfsFuture));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private CompletableFuture<Map<String, Object>> fanOut(
            ExecutorService pool, String name, java.util.function.Supplier<Map<String, Object>> call) {
        return CompletableFuture.supplyAsync(call, pool).exceptionally(t -> {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            log.warn("RichSearchTool: {} failed: {}", name, cause.toString());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", cause.getMessage() == null ? cause.toString() : cause.getMessage());
            err.put("results", List.of());
            err.put("count", 0);
            return err;
        });
    }

    /**
     * Strips the per-tool top-level {@code query} from a downstream
     * result so the bucket doesn't echo it. Keeps everything else
     * intact — {@code results}, {@code count}, {@code dropped_count},
     * {@code total_count}, {@code error}, {@code note}.
     */
    private static Map<String, Object> toBucket(Map<String, Object> raw) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        if (raw == null) {
            bucket.put("results", List.of());
            bucket.put("count", 0);
            return bucket;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if ("query".equals(e.getKey())) continue;
            bucket.put(e.getKey(), e.getValue());
        }
        return bucket;
    }

    private static int bucketCount(CompletableFuture<Map<String, Object>> f) {
        if (!f.isDone() || f.isCompletedExceptionally()) return 0;
        try {
            Map<String, Object> r = f.get();
            Object c = r == null ? null : r.get("count");
            return c instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
