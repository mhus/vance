package de.mhus.vance.brain.zarniwoop.protocols;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Test-seam for the JSON search APIs that either carry credentials or whose
 * caller needs the response headers: Exa (POST + {@code x-api-key}), Firecrawl
 * (POST + optional {@code Authorization: Bearer}, plus one GET for the
 * credit-usage probe) and SearXNG (GET without a key — but a 429 there still
 * carries {@code Retry-After}).
 *
 * <p>Not a replacement for {@link SimpleHttpClient} (GET-only, key-free, no
 * headers) or {@link SerperHttpClient} (Serper's own auth shape) — those stay
 * as they are. This seam exists because two verbs and arbitrary headers would
 * leak auth shape into the key-free protocols if folded into
 * {@code SimpleHttpClient}, which that interface deliberately avoids.
 */
public interface JsonHttpClient {

    /** Status, body and lowercased response headers. */
    record JsonResponse(int statusCode, String body, Map<String, String> headers) {}

    /** GET with the given request headers. */
    JsonResponse get(URI url, Map<String, String> headers, Duration timeout) throws Exception;

    /** POST a JSON body with the given request headers. */
    JsonResponse post(URI url, Map<String, String> headers, String jsonBody, Duration timeout) throws Exception;

    /**
     * The upstream's {@code Retry-After} hint as an absolute instant, or
     * {@code null} when the response carries none or an unparseable one.
     * Both accepted forms: delta-seconds (what Firecrawl and Exa send) and
     * the HTTP-date form. A hint in the past is still returned — the reader
     * clamps it.
     */
    static @Nullable Instant retryAfter(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        String raw = headers.get("retry-after");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Instant.now().plusSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException notSeconds) {
            try {
                return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
            } catch (RuntimeException notADate) {
                return null;
            }
        }
    }

    /**
     * A short slice of an error body for an exception message — newlines
     * collapsed and length-capped, because this text ends up in logs and in
     * Agrajag's {@code bodyContains} matching and the remote decides it.
     */
    static String bodyExcerpt(@Nullable String body, int maxChars) {
        if (body == null) {
            return "";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        return collapsed.substring(0, maxChars) + "…";
    }

    /** Production wiring against the JDK {@link HttpClient}. */
    final class JdkJsonHttpClient implements JsonHttpClient {

        private final HttpClient client;

        public JdkJsonHttpClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build());
        }

        JdkJsonHttpClient(HttpClient client) {
            this.client = client;
        }

        @Override
        public JsonResponse get(URI url, Map<String, String> headers, Duration timeout) throws Exception {
            HttpRequest request = request(url, headers, timeout).GET().build();
            return toResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        }

        @Override
        public JsonResponse post(URI url, Map<String, String> headers, String jsonBody, Duration timeout)
                throws Exception {
            HttpRequest request = request(url, headers, timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            return toResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        }

        private static HttpRequest.Builder request(URI url, Map<String, String> headers, Duration timeout) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(url)
                    .header("Accept", "application/json")
                    .timeout(timeout);
            if (headers != null) {
                headers.forEach(builder::header);
            }
            return builder;
        }

        private static JsonResponse toResponse(HttpResponse<String> r) {
            Map<String, String> headers = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
                }
            });
            return new JsonResponse(r.statusCode(), r.body() == null ? "" : r.body(), headers);
        }
    }
}
