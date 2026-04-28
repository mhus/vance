package de.mhus.vance.brain.tools.web;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fetch a single URL via HTTP GET and return the response body as
 * text. Complementary to {@link WebSearchTool}: search produces URLs,
 * fetch retrieves their content.
 *
 * <p>Output is truncated to {@link #MAX_BODY_CHARS} characters; the
 * full size is reported in {@code contentLength} so the caller can
 * decide whether to fetch a different page or accept the truncation.
 *
 * <p>Schemes other than {@code http} / {@code https} are rejected to
 * avoid accidental local-file or {@code file://} access. There is no
 * loopback-blocklist — this is a hub-side tool, not a worker tool, and
 * the engines that get it (Vance, Ford) are trusted to operate inside
 * the user's own scope.
 */
@Component
@Slf4j
public class WebFetchTool implements Tool {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Truncation budget for the response body, in characters. */
    private static final int MAX_BODY_CHARS = 50_000;

    /** Default user-agent — readable in server logs, unambiguous about origin. */
    private static final String USER_AGENT = "Vance-Brain/0.1 (+https://github.com/mhus/vance)";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "url", Map.of(
                            "type", "string",
                            "description", "Absolute http:// or https:// URL to fetch."),
                    "accept", Map.of(
                            "type", "string",
                            "description", "Optional Accept header (e.g. "
                                    + "'application/json'). Defaults to '*/*'.")),
            "required", List.of("url"));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch the content of a specific http(s) URL and return "
                + "the body as text. Use this when you have a concrete "
                + "URL (often from web_search results) and need the page "
                + "content. Body is truncated past " + MAX_BODY_CHARS
                + " characters — fullLength reports the original size.";
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
        String rawUrl = params == null ? null : (String) params.get("url");
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ToolException("'url' is required");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new ToolException("Invalid URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http")
                        && !scheme.equalsIgnoreCase("https"))) {
            throw new ToolException(
                    "Only http:// and https:// URLs are supported (got '"
                            + scheme + "')");
        }

        String accept = params == null ? null : (String) params.get("accept");
        if (accept == null || accept.isBlank()) {
            accept = "*/*";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());

            String body = response.body() == null ? "" : response.body();
            int fullLength = body.length();
            boolean truncated = fullLength > MAX_BODY_CHARS;
            String content = truncated
                    ? body.substring(0, MAX_BODY_CHARS) : body;

            String contentType = response.headers()
                    .firstValue("content-type").orElse("");

            log.info("WebFetchTool tenant='{}' url='{}' status={} bytes={}",
                    ctx.tenantId(), truncate(rawUrl, 120),
                    response.statusCode(), fullLength);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", uri.toString());
            out.put("status", response.statusCode());
            if (!contentType.isEmpty()) {
                out.put("contentType", contentType);
            }
            out.put("contentLength", fullLength);
            out.put("truncated", truncated);
            out.put("content", content);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while fetching '" + rawUrl + "'");
        } catch (Exception e) {
            log.warn("WebFetchTool tenant='{}' url='{}' failed: {}",
                    ctx.tenantId(), truncate(rawUrl, 120), e.toString());
            throw new ToolException("Fetch failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
