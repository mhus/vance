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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the JDK {@link HttpClient} that talks to the
 * Brain on behalf of Anus. Each tenant-scoped call mints a fresh admin token
 * via {@link AnusTokenService} and attaches it as a bearer header. No retry,
 * no connection pooling on top of what
 * {@code HttpClient} already gives — Anus is interactive and any
 * transient failure should surface to the operator immediately.
 *
 * <p>The {@link Response} record carries the raw status/body pair —
 * <p><b>Two auth paths, not two roles.</b> {@link #internal} talks to
 * {@code /internal/**} with the shared {@code vance.internal.token} secret and
 * no JWT at all — the brain's access filter skips that prefix entirely. Which
 * path a command uses is therefore a statement about <em>who</em> may call it,
 * enforced by the transport rather than by a permission check, and it is the
 * reason a browser cannot reach those routes.
 *
 * <p>higher-level commands parse JSON themselves so this class stays
 * payload-agnostic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnusBrainClient {

    /** Must match {@code InternalAccessFilter.HEADER_INTERNAL_TOKEN} in the brain. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Vance-Internal-Token";

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

    /**
     * Calls an {@code /internal/**} route with the shared secret instead of a
     * minted JWT — the technical surface.
     *
     * <p><b>No tenant argument, and that is the point.</b> These routes carry no
     * tenant scoping at all; whoever holds the secret is trusted uniformly
     * across the cluster. Where a tenant matters it travels in the body, the way
     * {@code /internal/cluster/bring} has always done it.
     *
     * @throws BrainCallException when no internal token is configured — stated
     *     here rather than left to a 401 from the far end, because the two are
     *     indistinguishable to the reader and only one of them is fixable
     *     locally
     */
    public Response internal(String path, String method, @Nullable String jsonBody) {
        return internalAt(properties.getHttpBase(), path, method, jsonBody);
    }

    /**
     * Same as {@link #internal} against a specific pod instead of the configured
     * base.
     *
     * <p>Needed because not every {@code /internal/} route is servable by any
     * pod: {@code /internal/cluster/release} tears down in-memory state that
     * only exists in the holding process, so the call has to reach that one. The
     * placement writes are the opposite — row writes, servable anywhere — and
     * use {@link #internal}. Which of the two a command picks is a statement
     * about where the effect lives.
     *
     * @param baseUrl absolute prefix, {@code http://host:port}. A bare
     *     {@code host:port} from {@code BrainPodDocument.endpoint} must be
     *     normalised by the caller, same contract as {@link #getAt}.
     */
    public Response internalAt(
            String baseUrl, String path, String method, @Nullable String jsonBody) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new BrainCallException(
                    "vance.anus.brain.internal-token is not set — /internal routes need the "
                            + "same shared secret as the brain's vance.internal.token", null);
        }
        URI uri = URI.create(baseUrl + path);
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getHttpRequestTimeout())
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, body)
                .build();
        return exchange(request, method, uri);
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

        return exchange(request, method, uri);
    }

    /** Shared transport tail — the two auth paths differ only in the header. */
    private Response exchange(HttpRequest request, String method, URI uri) {
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
