package de.mhus.vance.brain.tools.web;

import de.mhus.vance.brain.tools.web.ImageValidatorService.ValidationResult;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Image search via Serper.dev {@code /images} endpoint. Each result is
 * pre-validated by {@link ImageValidatorService} before being returned
 * — the LLM never sees a dead URL, so it can drop the {@code imageUrl}
 * straight into {@code ![alt](url)} Markdown without an embed failure.
 *
 * <p>Driven by the same {@code web.serper.apiKey} tenant setting as
 * {@link WebSearchTool}.
 *
 * <p>Result shape: {@code results[]} carries only validated entries.
 * Dropped count is reported separately so the caller (and audit logs)
 * know how many entries the validator rejected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageSearchTool implements Tool {

    private static final String SERPER_IMAGES_URL = "https://google.serper.dev/images";
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "Natural-language image search query, "
                                    + "e.g. 'Lisbon tram', 'red panda baby'."),
                    "num", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum results to return (1–"
                                            + MAX_NUM + ", default "
                                            + DEFAULT_NUM
                                            + "). Note: validator may drop entries that "
                                            + "fail liveness check — final count can be "
                                            + "lower than requested.")),
            "required", List.of("query"));

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final ImageValidatorService validator;
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public String name() {
        return "image_search";
    }

    @Override
    public String description() {
        return "Search the web for images. Returns pre-validated entries — "
                + "each imageUrl has been liveness-checked against the source "
                + "host. Drop imageUrl straight into '![alt](url)' Markdown "
                + "to embed; the UI renders external https image URLs out of "
                + "the box. Prefer this over web_search when the user asks "
                + "to see pictures.";
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
        return "Deprecated — use research_search with modality=image instead.";
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
                    "Serper API key not configured (setting '" + WebSearchTool.SETTING_KEY
                            + "' in _vance / project / think-process). Ask the operator to set it.");
        }

        List<RawResult> raw;
        try {
            raw = callSerper(query, num, apiKey, tenantId);
        } catch (ToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while searching images");
        } catch (Exception e) {
            log.warn("ImageSearchTool tenant='{}' query='{}' failed: {}",
                    tenantId, truncate(query, 80), e.toString());
            return errorResult("Image search failed: " + e.getMessage());
        }

        if (raw.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("results", List.of());
            out.put("count", 0);
            out.put("dropped_count", 0);
            out.put("total_count", 0);
            return out;
        }

        List<String> urls = new ArrayList<>(raw.size());
        for (RawResult r : raw) urls.add(r.imageUrl);
        List<ValidationResult> verdicts = validator.validate(
                urls, tenantId, ctx.projectId(), ctx.processId());
        Map<String, ValidationResult> verdictByUrl = new HashMap<>();
        for (ValidationResult v : verdicts) verdictByUrl.put(v.getUrl(), v);

        List<Map<String, Object>> validRows = new ArrayList<>();
        int dropped = 0;
        for (RawResult r : raw) {
            ValidationResult v = verdictByUrl.get(r.imageUrl);
            if (v == null || !v.isOk()) {
                dropped++;
                log.debug("ImageSearchTool query='{}' dropped url='{}' reason='{}'",
                        truncate(query, 60), truncate(r.imageUrl, 120),
                        v == null ? "no_verdict" : v.getReason());
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            if (r.title != null && !r.title.isBlank()) row.put("title", r.title);
            row.put("imageUrl", r.imageUrl);
            if (r.thumbnailUrl != null && !r.thumbnailUrl.isBlank()) {
                row.put("thumbnailUrl", r.thumbnailUrl);
            }
            if (r.source != null && !r.source.isBlank()) row.put("source", r.source);
            if (r.sourceLink != null && !r.sourceLink.isBlank()) row.put("sourceLink", r.sourceLink);
            if (v.getContentType() != null) row.put("contentType", v.getContentType());
            validRows.add(row);
        }

        log.info("ImageSearchTool query='{}' total={} valid={} dropped={}",
                truncate(query, 80), raw.size(), validRows.size(), dropped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("results", validRows);
        out.put("count", validRows.size());
        out.put("dropped_count", dropped);
        out.put("total_count", raw.size());
        if (validRows.isEmpty() && dropped > 0) {
            out.put("note", "All " + dropped + " image URLs failed validation. "
                    + "The source pages may still be reachable via web_search — "
                    + "let the user know the live image set is empty.");
        }
        return out;
    }

    private List<RawResult> callSerper(String query, int num, String apiKey, String tenantId)
            throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("q", query, "num", num));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERPER_IMAGES_URL))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Serper /images returned status {} for tenant='{}': {}",
                    response.statusCode(), tenantId, truncate(response.body(), 200));
            throw new ToolException("Image search returned status " + response.statusCode());
        }
        return parseSerper(response.body());
    }

    List<RawResult> parseSerper(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode images = root.path("images");
        List<RawResult> rows = new ArrayList<>();
        if (!images.isArray()) return rows;
        for (JsonNode item : images) {
            String imageUrl = item.path("imageUrl").asText("");
            if (imageUrl.isBlank()) continue;
            RawResult r = new RawResult();
            r.title = item.path("title").asText("");
            r.imageUrl = imageUrl;
            r.thumbnailUrl = item.path("thumbnailUrl").asText("");
            r.source = item.path("source").asText("");
            r.sourceLink = item.path("link").asText("");
            rows.add(r);
        }
        return rows;
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

    /** Pre-validation Serper row. Plain POJO — package-private. */
    static final class RawResult {
        String title = "";
        String imageUrl = "";
        String thumbnailUrl = "";
        String source = "";
        String sourceLink = "";
    }
}
