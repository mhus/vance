package de.mhus.vance.brain.jaglan.protocols;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Test seam for the {@code ode} file protocol. One JDK client backs the
 * production wiring; tests substitute a recorder.
 *
 * <p><b>Why not {@code CentauriHttpClient}.</b> That one models a response as
 * {@code (status, String body)}, which is right for JSON pages and wrong here:
 * a mount exists so a large file can be read without a copy on either side, and
 * a {@code String} body puts one in the middle. Hence {@link #getStream} and
 * {@link #put} taking and returning streams — the JSON calls keep the simple
 * shape, because a capabilities response is small by construction.
 */
public interface JaglanHttpClient {

    /** A JSON response, small enough to hold. */
    record Response(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * A streamed response. {@code body} is {@code null} for a non-success
     * status — there is nothing to stream and the caller must not have to
     * remember to close a stream it was never going to read.
     */
    record StreamResponse(int statusCode, InputStream body, Map<String, String> headers)
            implements AutoCloseable {

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        @Override
        public void close() {
            try {
                if (body != null) body.close();
            } catch (IOException ignored) {
                // Best effort: the caller is discarding this response anyway.
            }
        }
    }

    Response get(URI url, Map<String, String> headers, Duration timeout) throws Exception;

    /**
     * GET whose body stays a stream. The caller owns it and must close it —
     * including on the non-success path, where {@link StreamResponse#close()}
     * is a no-op.
     */
    StreamResponse getStream(URI url, Map<String, String> headers, Duration timeout)
            throws Exception;

    /** PUT a stream, expect a small JSON answer. */
    Response put(URI url, InputStream body, Map<String, String> headers, Duration timeout)
            throws Exception;

    Response delete(URI url, Map<String, String> headers, Duration timeout) throws Exception;

    /** Production wiring against the JDK client. */
    final class JdkJaglanHttpClient implements JaglanHttpClient {

        private final HttpClient client;

        public JdkJaglanHttpClient() {
            this(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build());
        }

        JdkJaglanHttpClient(HttpClient client) {
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
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }

        @Override
        public StreamResponse getStream(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(url)
                    // No Accept: the answer is whatever the file is.
                    .timeout(timeout)
                    .GET();
            headers.forEach(request::header);
            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            Map<String, String> responseHeaders = flatten(response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Drain and drop: leaving an error body unread holds the
                // connection until the pool times it out.
                try (InputStream errorBody = response.body()) {
                    errorBody.readAllBytes();
                } catch (IOException ignored) {
                    // Nothing to salvage from a body we are discarding.
                }
                return new StreamResponse(response.statusCode(), null, responseHeaders);
            }
            return new StreamResponse(response.statusCode(), response.body(), responseHeaders);
        }

        @Override
        public Response put(URI url, InputStream body, Map<String, String> headers,
                Duration timeout) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/octet-stream")
                    .timeout(timeout)
                    // Streamed rather than byte-array: the point of the mount
                    // is that the content never sits in memory whole.
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> body));
            headers.forEach(request::header);
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }

        @Override
        public Response delete(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(url)
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .DELETE();
            headers.forEach(request::header);
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }

        private static Map<String, String> flatten(HttpResponse<?> response) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) out.put(name.toLowerCase(), values.get(0));
            });
            return out;
        }
    }
}
