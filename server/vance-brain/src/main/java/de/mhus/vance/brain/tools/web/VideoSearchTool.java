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
 * Video search via Serper.dev {@code /videos} endpoint. Filters out
 * non-YouTube hosts and entries whose YouTube oEmbed check says
 * "not embeddable" — the LLM only sees live, embeddable videos, so
 * dropping the returned {@code embedFence} string straight into a
 * reply renders cleanly in the Web-UI without follow-up failures.
 *
 * <p>v1 is YouTube-only. Vimeo, Twitch, and bare {@code .mp4} URLs
 * surface in Serper's results but our inline fence renderer only
 * speaks YouTube; we drop them at the filter rather than embed
 * something the renderer can't handle.
 *
 * <p>Driven by the same {@code web.serper.apiKey} tenant setting as
 * {@link WebSearchTool}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoSearchTool implements Tool {

    private static final String SERPER_VIDEOS_URL = "https://google.serper.dev/videos";
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Natural-language video search query, "
                                            + "e.g. 'Lisbon tram 28 ride', "
                                            + "'how to make sourdough starter'."),
                    "num", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum results to return (1–"
                                            + MAX_NUM + ", default "
                                            + DEFAULT_NUM + "). Validator may "
                                            + "drop entries whose YouTube embed "
                                            + "is disabled — final count can be "
                                            + "lower than requested.")),
            "required", List.of("query"));

    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final YouTubeValidatorService validator;
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public String name() {
        return "video_search";
    }

    @Override
    public String description() {
        return "Search the web for videos (YouTube). Returns "
                + "pre-validated entries — each video has been checked "
                + "for embed availability via YouTube oEmbed. Each result "
                + "carries an `embedFence` field with a ready-to-paste "
                + "```youtube fenced block. **Copy `embedFence` VERBATIM** "
                + "into your reply — do not rewrite the body as YAML "
                + "(`id: ...`, `title: ...`) or change the URL form. "
                + "Other fields (title, channel, duration) are context "
                + "for your prose around the fence, not for inside it. "
                + "Prefer this over web_search when the user asks for a "
                + "video, tutorial, ride-along, or 'show me how X looks'.";
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
        int num = clampNum(params == null ? null : params.get("num"));

        String tenantId = ctx.tenantId();
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, ctx.projectId(), ctx.processId(), WebSearchTool.SETTING_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            return errorResult(
                    "Serper API key not configured (setting '" + WebSearchTool.SETTING_KEY
                            + "' in _vance / project / think-process). "
                            + "Ask the operator to set it.");
        }

        List<RawResult> raw;
        try {
            raw = callSerper(query, num, apiKey, tenantId);
        } catch (ToolException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while searching videos");
        } catch (Exception e) {
            log.warn("VideoSearchTool tenant='{}' query='{}' failed: {}",
                    tenantId, truncate(query, 80), e.toString());
            return errorResult("Video search failed: " + e.getMessage());
        }

        List<Map<String, Object>> validRows = new ArrayList<>();
        int dropped = 0;
        for (RawResult r : raw) {
            String videoId = YouTubeValidatorService.extractVideoId(r.videoLink);
            if (videoId == null) {
                dropped++;
                log.debug("VideoSearchTool query='{}' dropped non-YouTube link='{}'",
                        truncate(query, 60), truncate(r.videoLink, 120));
                continue;
            }
            if (!validator.isEmbeddable(videoId)) {
                dropped++;
                log.debug("VideoSearchTool query='{}' dropped non-embeddable id='{}' link='{}'",
                        truncate(query, 60), videoId, truncate(r.videoLink, 120));
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            if (r.title != null && !r.title.isBlank()) row.put("title", r.title);
            row.put("videoId", videoId);
            row.put("videoUrl", "https://youtu.be/" + videoId);
            if (r.thumbnailUrl != null && !r.thumbnailUrl.isBlank()) {
                row.put("thumbnailUrl", r.thumbnailUrl);
            }
            if (r.duration != null && !r.duration.isBlank()) row.put("duration", r.duration);
            if (r.channel != null && !r.channel.isBlank()) row.put("channel", r.channel);
            if (r.date != null && !r.date.isBlank()) row.put("date", r.date);
            row.put("embedFence",
                    "```youtube\nhttps://youtu.be/" + videoId + "\n```");
            validRows.add(row);
        }

        log.info("VideoSearchTool query='{}' total={} valid={} dropped={}",
                truncate(query, 80), raw.size(), validRows.size(), dropped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("results", validRows);
        out.put("count", validRows.size());
        out.put("dropped_count", dropped);
        out.put("total_count", raw.size());
        if (validRows.isEmpty() && dropped > 0) {
            out.put("note", "All " + dropped + " video URLs failed validation "
                    + "(non-YouTube host or embed disabled). Search returned "
                    + "results but none are inline-playable.");
        }
        return out;
    }

    private List<RawResult> callSerper(String query, int num, String apiKey, String tenantId)
            throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("q", query, "num", num));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERPER_VIDEOS_URL))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Serper /videos returned status {} for tenant='{}': {}",
                    response.statusCode(), tenantId, truncate(response.body(), 200));
            throw new ToolException("Video search returned status " + response.statusCode());
        }
        return parseSerper(response.body());
    }

    List<RawResult> parseSerper(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode videos = root.path("videos");
        List<RawResult> rows = new ArrayList<>();
        if (!videos.isArray()) return rows;
        for (JsonNode item : videos) {
            String link = item.path("link").asText("");
            if (link.isBlank()) continue;
            RawResult r = new RawResult();
            r.title = item.path("title").asText("");
            r.videoLink = link;
            r.thumbnailUrl = item.path("imageUrl").asText("");
            r.duration = item.path("duration").asText("");
            r.channel = item.path("channel").asText("");
            r.date = item.path("date").asText("");
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
        String videoLink = "";
        String thumbnailUrl = "";
        String duration = "";
        String channel = "";
        String date = "";
    }
}
