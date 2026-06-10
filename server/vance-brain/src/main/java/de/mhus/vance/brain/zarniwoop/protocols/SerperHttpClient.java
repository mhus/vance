package de.mhus.vance.brain.zarniwoop.protocols;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Test-seam for outgoing Serper HTTP calls. Production wiring is
 * {@link JdkSerperHttpClient} backed by the JDK {@link HttpClient};
 * tests replace this with a recorder to assert request shape and
 * inject canned responses.
 */
public interface SerperHttpClient {

    /** Response wrapper — body, status, headers as the dispatcher needs them. */
    record SerperResponse(int statusCode, String body, Map<String, String> headers) { }

    /**
     * Issue a POST to {@code url} with the given JSON body and the
     * {@code X-API-KEY} header. Status, body and lowercased response
     * headers come back; the caller decides what to do with them.
     */
    SerperResponse post(URI url, String apiKey, String jsonBody, Duration timeout)
            throws Exception;

    /** Issue a GET to {@code url} with the {@code X-API-KEY} header. */
    SerperResponse get(URI url, String apiKey, Duration timeout) throws Exception;

    /** Default implementation against the real Serper service. */
    final class JdkSerperHttpClient implements SerperHttpClient {

        private final HttpClient client;

        public JdkSerperHttpClient() {
            this(HttpClient.newHttpClient());
        }

        JdkSerperHttpClient(HttpClient client) {
            this.client = client;
        }

        @Override
        public SerperResponse post(URI url, String apiKey,
                                   String jsonBody, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return toResponse(response);
        }

        @Override
        public SerperResponse get(URI url, String apiKey, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("X-API-KEY", apiKey)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return toResponse(response);
        }

        private static SerperResponse toResponse(HttpResponse<String> r) {
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
            });
            return new SerperResponse(r.statusCode(),
                    r.body() == null ? "" : r.body(),
                    headers);
        }
    }
}
