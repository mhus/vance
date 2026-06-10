package de.mhus.vance.brain.tools.web;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.settings.SettingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PDF search via Serper.dev's {@code /search} endpoint with a
 * {@code filetype:pdf} query modifier. Each hit is HEAD-probed
 * before being returned so the LLM only sees PDF URLs that
 * actually serve a PDF payload — no dead links, no HTML error
 * pages that pretended to be {@code .pdf}.
 *
 * <p>Counterpart to {@link ImageSearchTool} and
 * {@link VideoSearchTool}; same Serper key, same pre-validation
 * philosophy. PDFs land in the Web-UI as Slack-style link cards
 * through {@code LinkPreviewService} ({@code type=pdf} fallback)
 * — no inline embed renderer needed, the card with title +
 * hostname is the right shape.
 *
 * <p>Tuning: {@code web.pdfSearch.timeoutMs} (default 2000) +
 * {@code web.pdfSearch.totalBudgetMs} (default 5000) +
 * {@code web.pdfSearch.maxConcurrent} (default 10) through the
 * project cascade.
 */
@Component
@Slf4j
public class PdfSearchTool implements Tool {

    private static final String SERPER_SEARCH_URL = "https://google.serper.dev/search";
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;
    private static final Duration SERPER_TIMEOUT = Duration.ofSeconds(15);

    static final String SETTING_TIMEOUT_MS = "web.pdfSearch.timeoutMs";
    static final String SETTING_TOTAL_BUDGET_MS = "web.pdfSearch.totalBudgetMs";
    static final String SETTING_MAX_CONCURRENT = "web.pdfSearch.maxConcurrent";

    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_TOTAL_BUDGET_MS = 5000;
    private static final int DEFAULT_MAX_CONCURRENT = 10;

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Natural-language search query. The tool "
                                            + "appends 'filetype:pdf' to the "
                                            + "Serper request — pass the topic, "
                                            + "not the modifier (e.g. "
                                            + "'EU AI Act final text', "
                                            + "'Linux kernel networking guide')."),
                    "num", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum results to return (1–"
                                            + MAX_NUM + ", default "
                                            + DEFAULT_NUM + "). Validator may "
                                            + "drop entries whose HEAD doesn't "
                                            + "advertise application/pdf — final "
                                            + "count can be lower than requested.")),
            "required", List.of("query"));

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final PdfHttp http;
    private final HttpClient serperHttp;

    @Autowired
    public PdfSearchTool(SettingService settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new JdkPdfHttp(), HttpClient.newHttpClient());
    }

    /** Test-seam constructor. */
    PdfSearchTool(SettingService settings, ObjectMapper objectMapper,
                  PdfHttp http, HttpClient serperHttp) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.http = http;
        this.serperHttp = serperHttp;
    }

    @Override
    public String name() {
        return "pdf_search";
    }

    @Override
    public String description() {
        return "Search the web for PDF documents. Returns pre-"
                + "validated entries — each URL has been HEAD-probed "
                + "and confirmed to serve an application/pdf payload, "
                + "so dead links and HTML-pretending-to-be-PDF pages "
                + "never reach you. Present hits to the user as plain "
                + "Markdown links: '[Title](url)'. The Web-UI renders "
                + "each link as a card with title + hostname; you do "
                + "not need to embed the PDF inline. Prefer this over "
                + "web_search when the user asks for papers, reports, "
                + "standards, manuals, or 'find me the PDF of X'.";
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
        return "Deprecated — use research_search with modality=pdf instead.";
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
        int num = clampNum(params == null ? null : params.get("num"));

        String tenantId = ctx.tenantId();
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, ctx.projectId(), ctx.processId(), WebSearchTool.SETTING_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            return errorResult(
                    "Serper API key not configured (setting '"
                            + WebSearchTool.SETTING_KEY
                            + "' in _vance / project / think-process).");
        }

        List<RawResult> raw;
        try {
            raw = callSerper(query, num, apiKey, tenantId);
        } catch (ToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while searching PDFs");
        } catch (Exception e) {
            log.warn("PdfSearchTool tenant='{}' query='{}' failed: {}",
                    tenantId, truncate(query, 80), e.toString());
            return errorResult("PDF search failed: " + e.getMessage());
        }

        PdfConfig cfg = configFor(tenantId, ctx.projectId(), ctx.processId());
        Map<String, ValidationVerdict> verdicts = validateAll(raw, cfg);

        List<Map<String, Object>> validRows = new ArrayList<>();
        int dropped = 0;
        for (RawResult r : raw) {
            ValidationVerdict v = verdicts.get(r.url);
            if (v == null || !v.ok) {
                dropped++;
                log.debug("PdfSearchTool query='{}' dropped url='{}' reason='{}'",
                        truncate(query, 60), truncate(r.url, 120),
                        v == null ? "no_verdict" : v.reason);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            if (r.title != null && !r.title.isBlank()) row.put("title", r.title);
            row.put("url", v.finalUrl == null ? r.url : v.finalUrl);
            if (r.source != null && !r.source.isBlank()) row.put("source", r.source);
            if (r.snippet != null && !r.snippet.isBlank()) row.put("snippet", r.snippet);
            if (v.contentLength > 0) row.put("sizeBytes", v.contentLength);
            validRows.add(row);
        }

        log.info("PdfSearchTool query='{}' total={} valid={} dropped={}",
                truncate(query, 80), raw.size(), validRows.size(), dropped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("results", validRows);
        out.put("count", validRows.size());
        out.put("dropped_count", dropped);
        out.put("total_count", raw.size());
        if (validRows.isEmpty() && dropped > 0) {
            out.put("note", "All " + dropped + " hits failed the PDF "
                    + "content-type check. The links may still load in a "
                    + "browser (sometimes hosts serve PDFs as html-with-"
                    + "embed); you can fall back to web_search if the "
                    + "user needs the source pages.");
        }
        return out;
    }

    private List<RawResult> callSerper(String query, int num, String apiKey, String tenantId)
            throws Exception {
        // Serper takes the modifier inline in the q parameter — same
        // shape Google search uses. The LLM passes the raw topic; we
        // own the syntax so the modifier can't drift.
        String pdfQuery = query + " filetype:pdf";
        String requestBody = objectMapper.writeValueAsString(
                Map.of("q", pdfQuery, "num", num));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERPER_SEARCH_URL))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .timeout(SERPER_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = serperHttp.send(
                request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Serper /search (pdf) returned status {} for tenant='{}': {}",
                    response.statusCode(), tenantId, truncate(response.body(), 200));
            throw new ToolException("PDF search returned status " + response.statusCode());
        }
        return parseSerper(response.body());
    }

    List<RawResult> parseSerper(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode organic = root.path("organic");
        List<RawResult> rows = new ArrayList<>();
        if (!organic.isArray()) return rows;
        for (JsonNode item : organic) {
            String link = item.path("link").asText("");
            if (link.isBlank()) continue;
            RawResult r = new RawResult();
            r.title = item.path("title").asText("");
            r.url = link;
            r.source = item.path("source").asText("");
            r.snippet = item.path("snippet").asText("");
            rows.add(r);
        }
        return rows;
    }

    private Map<String, ValidationVerdict> validateAll(
            List<RawResult> raw, PdfConfig cfg) {
        Map<String, ValidationVerdict> out = new HashMap<>();
        if (raw.isEmpty()) return out;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(cfg.maxConcurrent, raw.size())));
        try {
            Map<String, CompletableFuture<ValidationVerdict>> futures = new LinkedHashMap<>();
            for (RawResult r : raw) {
                if (futures.containsKey(r.url)) continue;
                String url = r.url;
                futures.put(url, CompletableFuture.supplyAsync(
                        () -> probe(url, cfg), pool));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.values().toArray(new CompletableFuture[0]));
            try {
                all.get(cfg.totalBudgetMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("PdfSearchTool: total budget {}ms exhausted, "
                        + "{} of {} URLs incomplete",
                        cfg.totalBudgetMs,
                        futures.values().stream().filter(f -> !f.isDone()).count(),
                        futures.size());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            for (Map.Entry<String, CompletableFuture<ValidationVerdict>> e : futures.entrySet()) {
                if (e.getValue().isDone() && !e.getValue().isCompletedExceptionally()) {
                    try {
                        out.put(e.getKey(), e.getValue().get());
                    } catch (Exception inner) {
                        out.put(e.getKey(),
                                ValidationVerdict.fail("future_failed: " + inner.getMessage()));
                    }
                } else {
                    e.getValue().cancel(true);
                    out.put(e.getKey(), ValidationVerdict.fail("budget_exhausted"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private ValidationVerdict probe(String url, PdfConfig cfg) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return ValidationVerdict.fail("invalid_uri");
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
            return ValidationVerdict.fail("scheme_not_http");
        }
        try {
            return http.head(uri, Duration.ofMillis(cfg.timeoutMs), BROWSER_USER_AGENT);
        } catch (Exception e) {
            return ValidationVerdict.fail("head_failed: " + e.getClass().getSimpleName());
        }
    }

    static int clampNum(Object raw) {
        int n = DEFAULT_NUM;
        if (raw instanceof Number number) {
            n = number.intValue();
        } else if (raw instanceof String s && !s.isBlank()) {
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

    private PdfConfig configFor(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String processId) {
        return PdfConfig.builder()
                .timeoutMs(intSetting(tenantId, projectId, processId,
                        SETTING_TIMEOUT_MS, DEFAULT_TIMEOUT_MS))
                .totalBudgetMs(intSetting(tenantId, projectId, processId,
                        SETTING_TOTAL_BUDGET_MS, DEFAULT_TOTAL_BUDGET_MS))
                .maxConcurrent(intSetting(tenantId, projectId, processId,
                        SETTING_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT))
                .build();
    }

    private int intSetting(
            @Nullable String tenantId, @Nullable String projectId, @Nullable String processId,
            String key, int defaultValue) {
        if (tenantId == null || tenantId.isBlank()) return defaultValue;
        String raw = settings.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        out.put("results", List.of());
        out.put("count", 0);
        out.put("dropped_count", 0);
        out.put("total_count", 0);
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static final class RawResult {
        String title = "";
        String url = "";
        String source = "";
        String snippet = "";
    }

    @lombok.Builder
    @Value
    static class PdfConfig {
        int timeoutMs;
        int totalBudgetMs;
        int maxConcurrent;
    }

    @Value
    static class ValidationVerdict {
        boolean ok;
        @Nullable String reason;
        @Nullable String finalUrl;
        @Nullable String contentType;
        long contentLength;

        static ValidationVerdict ok(String finalUrl, String contentType, long contentLength) {
            return new ValidationVerdict(true, null, finalUrl, contentType, contentLength);
        }

        static ValidationVerdict fail(String reason) {
            return new ValidationVerdict(false, reason, null, null, 0L);
        }
    }

    /** Test-seam for the HEAD probe + the Serper call. */
    interface PdfHttp {
        ValidationVerdict head(URI uri, Duration timeout, String userAgent) throws Exception;
    }

    static final class JdkPdfHttp implements PdfHttp {

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        @Override
        public ValidationVerdict head(URI uri, Duration timeout, String userAgent) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/pdf,*/*;q=0.5")
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = http.send(
                    request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            String contentType = response.headers().firstValue("content-type").orElse("");
            String finalUrl = response.uri().toString();
            if (status >= 400) {
                return ValidationVerdict.fail("status_" + status);
            }
            String ct = contentType.toLowerCase();
            int semi = ct.indexOf(';');
            if (semi > 0) ct = ct.substring(0, semi).trim();
            if (!ct.startsWith("application/pdf")) {
                return ValidationVerdict.fail("content_type_" + (ct.isEmpty() ? "missing" : ct));
            }
            long length = response.headers().firstValueAsLong("content-length").orElse(0L);
            return ValidationVerdict.ok(finalUrl, contentType, length);
        }
    }
}
