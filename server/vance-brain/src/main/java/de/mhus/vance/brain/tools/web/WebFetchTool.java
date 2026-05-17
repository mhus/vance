package de.mhus.vance.brain.tools.web;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
 * the engines that get it (Eddie, Ford) are trusted to operate inside
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

    /** Recognised tokens inside the {@code flags} parameter. */
    static final String FLAG_NO_LLMS = "no-llms";
    /**
     * Legacy alias for the default behaviour — kept so old callers
     * that explicitly pass {@code flags="text"} keep working unchanged.
     * As of 2026-05-17 HTML pages are returned as extracted body text
     * by default; setting this flag is now a no-op.
     */
    static final String FLAG_TEXT = "text";
    /**
     * Opt-out from the HTML-to-text default: returns the raw markup
     * verbatim, the way the server sent it. Use this for tasks that
     * inspect tag structure, attributes, scripts, or meta tags. For
     * normal "read the article" use cases leave this off — the
     * default text extraction strips boilerplate and keeps prose,
     * which the LLM (and the 32 KB tool-result-truncation threshold)
     * both prefer.
     */
    static final String FLAG_RAW = "raw";
    private static final Set<String> KNOWN_FLAGS = Set.of(FLAG_NO_LLMS, FLAG_TEXT, FLAG_RAW);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "url", Map.of(
                            "type", "string",
                            "description", "Absolute http:// or https:// URL to fetch."),
                    "accept", Map.of(
                            "type", "string",
                            "description", "Optional Accept header (e.g. "
                                    + "'application/json'). Defaults to '*/*'."),
                    "flags", Map.of(
                            "type", "string",
                            "description", "Optional comma- or space-separated tokens. "
                                    + "Recognised: 'raw' returns the original markup "
                                    + "verbatim instead of the default extracted body "
                                    + "text (use only when you need tag structure, "
                                    + "scripts, or meta-data); 'no-llms' skips the "
                                    + "per-origin llms.txt overview probe; 'text' is "
                                    + "a legacy no-op (text extraction is now the "
                                    + "default for HTML pages). Unknown tokens are "
                                    + "ignored.")),
            "required", List.of("url"));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    private final LlmsTxtProbeService llmsTxtProbe;

    public WebFetchTool(LlmsTxtProbeService llmsTxtProbe) {
        this.llmsTxtProbe = llmsTxtProbe;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch the content of a specific http(s) URL and return "
                + "the body. HTML pages are parsed and returned as "
                + "extracted visible text by default (script/style content "
                + "stripped, entities decoded, whitespace normalised) — "
                + "the usual case when you want the article's prose. JSON, "
                + "plain-text and other non-HTML content passes through "
                + "unchanged. Body is truncated past " + MAX_BODY_CHARS
                + " characters — contentLength reports the original size. "
                + "When the origin publishes an llms.txt overview, the "
                + "response also carries an 'originOverview' field with "
                + "a curated index of the site (cached per origin). "
                + "Optional 'flags' parameter — comma- or space-separated "
                + "tokens — controls behaviour: 'raw' opts out of HTML "
                + "text extraction and returns the original markup; "
                + "'no-llms' skips the originOverview probe entirely.";
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

        Set<String> flags = parseFlags(params == null ? null : params.get("flags"));

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
            String contentType = response.headers()
                    .firstValue("content-type").orElse("");

            // HTML pages are parsed to extracted body text by default —
            // raw markup costs an order of magnitude more tokens and
            // is rarely what the caller actually wants (boilerplate
            // <head>, scripts, preload links). Callers that genuinely
            // need the markup set flags="raw" to opt out. Non-HTML
            // content (JSON, plain text) is never transformed.
            boolean transformedFromHtml = false;
            String effectiveBody = body;
            if (!flags.contains(FLAG_RAW) && looksLikeHtml(contentType, body)) {
                effectiveBody = htmlToText(body);
                transformedFromHtml = true;
            }

            int fullLength = effectiveBody.length();
            boolean truncated = fullLength > MAX_BODY_CHARS;
            String content = truncated
                    ? effectiveBody.substring(0, MAX_BODY_CHARS) : effectiveBody;

            log.info("WebFetchTool tenant='{}' url='{}' status={} bytes={} flags={}",
                    ctx.tenantId(), truncate(rawUrl, 120),
                    response.statusCode(), body.length(), flags);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", uri.toString());
            out.put("status", response.statusCode());
            if (!contentType.isEmpty()) {
                out.put("contentType", contentType);
            }
            out.put("contentLength", fullLength);
            out.put("truncated", truncated);
            if (transformedFromHtml) {
                out.put("transformedFromHtml", true);
            }
            out.put("content", content);

            if (!flags.contains(FLAG_NO_LLMS)) {
                Optional<String> overview = llmsTxtProbe.probe(uri, ctx);
                overview.ifPresent(overviewBody -> out.put("originOverview", Map.of(
                        "source", "llms.txt",
                        "content", overviewBody)));
            }
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

    /**
     * Parses the {@code flags} parameter into a normalised set of tokens.
     * Accepts comma- or whitespace-separated input, lowercases each
     * token, and silently drops unknown ones — the LLM should never
     * fail a fetch because it spelled a flag wrong.
     */
    static Set<String> parseFlags(Object raw) {
        if (raw == null) return Collections.emptySet();
        String s = raw.toString().trim();
        if (s.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String token : s.split("[\\s,]+")) {
            String norm = token.trim().toLowerCase();
            if (!norm.isEmpty() && KNOWN_FLAGS.contains(norm)) {
                out.add(norm);
            }
        }
        return out;
    }

    /**
     * Heuristic: treat the body as HTML when the {@code Content-Type}
     * advertises {@code text/html} or {@code application/xhtml+xml},
     * or — when the server omits the header — the body's leading
     * non-whitespace bytes look like a tag. Other content types
     * (JSON, plain text) are passed through untouched.
     */
    private static boolean looksLikeHtml(String contentType, String body) {
        if (contentType != null && !contentType.isEmpty()) {
            String ct = contentType.toLowerCase();
            return ct.contains("text/html") || ct.contains("application/xhtml");
        }
        // Sniff: a leading '<' followed by an ASCII letter — covers the
        // common "<!DOCTYPE html>" and "<html>" cases. Avoids parsing
        // JSON-with-stray-XML-strings as HTML.
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c != '<') return false;
            if (i + 1 >= body.length()) return false;
            char next = body.charAt(i + 1);
            return next == '!' || (next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z');
        }
        return false;
    }

    /**
     * Converts HTML to a plain-text representation: drops {@code <script>}
     * / {@code <style>} content, decodes entities, collapses whitespace.
     * Block-level boundaries become newlines so the structure of the
     * document survives in a readable form.
     */
    static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        Document doc = Jsoup.parse(html);
        // Suppress the pretty-printer; we want explicit \n boundaries that
        // we control, not Jsoup's HTML re-serialisation whitespace rules.
        doc.outputSettings().prettyPrint(false);
        // Insert newline markers around block-level elements so the
        // collapsed-whitespace text() output keeps paragraph breaks.
        for (var br : doc.select("br")) {
            br.append("\\n");
        }
        for (var block : doc.select("p, div, section, article, header, footer, "
                + "li, tr, h1, h2, h3, h4, h5, h6, blockquote, pre")) {
            block.prepend("\\n");
            block.append("\\n");
        }
        String text = doc.text();
        // Replace our literal "\n" markers with real newlines, then collapse
        // runs of blank lines to at most two for readability.
        text = text.replace("\\n", "\n");
        return text.replaceAll("\n[ \t]+", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

}
