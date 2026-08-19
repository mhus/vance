package de.mhus.vance.brain.centauri.protocols;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET/POST test-seam for the feed protocols. One JDK client backs the
 * production wiring; tests substitute a recorder.
 *
 * <p>Separate from Zarniwoop's {@code SimpleHttpClient} rather than shared:
 * that one is GET-only with a fixed User-Agent parameter, and reaching across
 * subsystems for a four-method interface would couple two stacks that have no
 * other reason to know about each other.
 *
 * <p>Headers travel as a map so a credential and the reader pseudonym fit
 * without either becoming a named parameter — a protocol that needs neither
 * passes an empty map.
 */
public interface CentauriHttpClient {

    record Response(int statusCode, String body) {

        /**
         * Public because the protocols that read it no longer all live in this
         * package — the example sources ship with the Feeds addon.
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    Response get(URI url, Map<String, String> headers, Duration timeout) throws Exception;

    Response postJson(URI url, String body, Map<String, String> headers, Duration timeout)
            throws Exception;

    /** Production wiring against the JDK client. */
    final class JdkCentauriHttpClient implements CentauriHttpClient {

        private final HttpClient client;

        public JdkCentauriHttpClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build());
        }

        JdkCentauriHttpClient(HttpClient client) {
            this.client = client;
        }

        @Override
        public Response get(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .GET();
            headers.forEach(request::header);
            return send(request.build());
        }

        @Override
        public Response postJson(
                URI url, String body, Map<String, String> headers, Duration timeout)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);
            return send(request.build());
        }

        private Response send(HttpRequest request) throws Exception {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(), response.body() == null ? "" : response.body());
        }
    }

    /** Append {@code ?k=v&k=v}, skipping empties, to a base URL. */
    static URI withQuery(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base);
        boolean first = base.indexOf('?') < 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return URI.create(sb.toString());
    }

    static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
