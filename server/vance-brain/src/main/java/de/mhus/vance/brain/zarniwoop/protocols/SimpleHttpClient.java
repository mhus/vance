package de.mhus.vance.brain.zarniwoop.protocols;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal GET-only HTTP test-seam reused by the unauthenticated
 * Zarniwoop protocols (Wikipedia, HackerNews, OpenLibrary,
 * OpenAlex, arXiv). One JDK client backs the production wiring; tests
 * substitute a recorder.
 *
 * <p>Serper sticks with its own {@link SerperHttpClient} because it
 * needs POST + the {@code X-API-KEY} header — keeping the simple
 * client free of those concerns avoids leaking auth shape into
 * key-free protocols.
 */
public interface SimpleHttpClient {

    record Response(int statusCode, String body) { }

    Response get(URI url, String userAgent, Duration timeout) throws Exception;

    /** Production wiring against the JDK HttpClient. */
    final class JdkSimpleHttpClient implements SimpleHttpClient {

        private final HttpClient client;

        public JdkSimpleHttpClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build());
        }

        JdkSimpleHttpClient(HttpClient client) {
            this.client = client;
        }

        @Override
        public Response get(URI url, String userAgent, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json,application/atom+xml,application/xml;q=0.9,*/*;q=0.5")
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> r = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body() == null ? "" : r.body());
        }
    }

    /** Helper used by protocols that build {@code ?k=v&k=v} URLs. */
    static String buildQuery(URI base, Map<String, String> params) {
        if (params == null || params.isEmpty()) return base.toString();
        StringBuilder sb = new StringBuilder(base.toString());
        sb.append(base.getQuery() == null ? '?' : '&');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Convenience for protocols that want a typed map. */
    static Map<String, String> mapOf(String... pairs) {
        if (pairs == null || pairs.length == 0) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
