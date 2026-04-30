package de.mhus.vance.brain.tools.web;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.settings.SettingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Web search via Serper.dev (Google SERPs as JSON). Ported from the
 * nimbus prototype, adapted to our tenant-scoped {@link SettingService}
 * and our {@link Tool} contract.
 *
 * <p>API key is read per call from {@code web.serper.apiKey} under the
 * tenant's {@code tenant:<tenantId>} scope as an encrypted PASSWORD
 * setting — rotate via the admin settings endpoint, no restart needed.
 * If the key is missing the tool surfaces a friendly error string
 * instead of throwing, so the LLM can notice and switch approach.
 *
 * <p>Register at <a href="https://serper.dev">serper.dev</a>.
 * Free tier: 2500 queries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSearchTool implements Tool {

    public static final String SETTING_KEY = "web.serper.apiKey";
    private static final String SERPER_API_URL = "https://google.serper.dev/search";
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "Natural-language search query."),
                    "num", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum number of results (1–"
                                            + MAX_NUM + ", default "
                                            + DEFAULT_NUM + ").")),
            "required", List.of("query"));

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for information about a topic. Returns titles, "
                + "URLs, and snippets. Use this for fresh facts or anything "
                + "you don't already know.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String query = params == null ? null : (String) params.get("query");
        if (query == null || query.isBlank()) {
            throw new ToolException("'query' is required");
        }
        int num = clampNum(params == null ? null : params.get("num"));

        String tenantId = ctx.tenantId();
        // ToolInvocationContext carries the full call scope, so the
        // serper-key lookup can honour project / process overrides
        // (e.g. a project pinning a stricter rate-limited key).
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, ctx.projectId(), ctx.processId(), SETTING_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            return errorResult(
                    "Serper API key not configured (setting '" + SETTING_KEY
                            + "' in _vance / project / think-process). Ask the operator to set it.");
        }

        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("q", query, "num", num));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERPER_API_URL))
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Serper returned status {} for tenant='{}': {}",
                        response.statusCode(), tenantId, truncate(response.body(), 200));
                return errorResult(
                        "Search returned status " + response.statusCode());
            }
            return parseResults(query, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while searching");
        } catch (Exception e) {
            log.warn("WebSearchTool tenant='{}' query='{}' failed: {}",
                    tenantId, truncate(query, 80), e.toString());
            return errorResult("Search failed: " + e.getMessage());
        }
    }

    private Map<String, Object> parseResults(String query, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode organic = root.path("organic");
            List<Map<String, Object>> rows = new ArrayList<>();
            if (organic.isArray()) {
                for (JsonNode item : organic) {
                    String title = item.path("title").asText("");
                    if (title.isBlank()) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", title);
                    row.put("url", item.path("link").asText(""));
                    String snippet = item.path("snippet").asText("");
                    if (!snippet.isBlank()) {
                        row.put("snippet", snippet);
                    }
                    rows.add(row);
                }
            }
            log.info("WebSearchTool query='{}' → {} results",
                    truncate(query, 80), rows.size());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("results", rows);
            out.put("count", rows.size());
            return out;
        } catch (Exception e) {
            log.warn("WebSearchTool: failed to parse Serper response: {}", e.toString());
            return errorResult("Failed to parse results: " + e.getMessage());
        }
    }

    private static int clampNum(Object raw) {
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
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
