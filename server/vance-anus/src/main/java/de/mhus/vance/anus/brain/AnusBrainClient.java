package de.mhus.vance.anus.brain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the JDK {@link HttpClient} that talks to the
 * Brain on behalf of Anus. Each call mints a fresh admin token via
 * {@link AnusTokenService} for the target tenant and attaches it as a
 * bearer header. No retry, no connection pooling on top of what
 * {@code HttpClient} already gives — Anus is interactive and any
 * transient failure should surface to the operator immediately.
 *
 * <p>The {@link Response} record carries the raw status/body pair —
 * higher-level commands parse JSON themselves so this class stays
 * payload-agnostic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnusBrainClient {

    private final AnusTokenService tokenService;
    private final AnusBrainProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Response get(String tenant, String path) {
        return getAt(properties.getHttpBase(), tenant, path);
    }

    public Response post(String tenant, String path, String jsonBody) {
        return send(properties.getHttpBase(), tenant, path, "POST", jsonBody);
    }

    public Response put(String tenant, String path, String jsonBody) {
        return send(properties.getHttpBase(), tenant, path, "PUT", jsonBody);
    }

    public Response delete(String tenant, String path) {
        return send(properties.getHttpBase(), tenant, path, "DELETE", null);
    }

    /**
     * Variant that overrides the configured {@code httpBase}. Used by
     * {@code cluster ping} so each pod is addressed at its own
     * advertised endpoint instead of the load-balanced default.
     *
     * @param baseUrl absolute URL prefix without trailing path
     *                ({@code http://10.0.0.1:9990}) — caller is
     *                responsible for the scheme; {@code host:port}
     *                strings from {@code BrainPodDocument.endpoint}
     *                must be normalised to {@code http://host:port}
     *                before being passed in
     */
    public Response getAt(String baseUrl, String tenant, String path) {
        return send(baseUrl, tenant, path, "GET", null);
    }

    private Response send(String baseUrl, String tenant, String path, String method, String jsonBody) {
        String token = tokenService.mintAdminToken(tenant);
        URI uri = URI.create(baseUrl + path);

        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getHttpRequestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, body)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            log.debug("{} {} → {}", method, uri, response.statusCode());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new BrainCallException(method + " " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrainCallException(method + " " + uri + " interrupted", e);
        }
    }

    /** Status code + raw response body. */
    public record Response(int statusCode, String body) {

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /** Wraps {@link IOException}/{@link InterruptedException} from {@link HttpClient#send}. */
    public static class BrainCallException extends RuntimeException {
        public BrainCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
