package de.mhus.vance.brain.workspace.access;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reusable Layer-1 → Layer-2 GET forwarder. Resolves the owner pod via
 * {@link WorkspaceRoutingCache}, sends the request with the shared
 * internal token, and parses the JSON body into the caller's target type.
 *
 * <p>Same retry behaviour as {@code WorkspaceController.proxyGet}: one
 * cache-invalidating retry on connect/timeout failure, then 503. The
 * wrapping in a Spring bean is so any controller that needs to read
 * pod-local state for a project can reuse it without duplicating the
 * boilerplate. See {@code specification/workspace-access.md} §2 / §8.
 */
@Component
@Slf4j
public class PodForwarder {

    private final WorkspaceRoutingCache routingCache;
    private final WorkspaceAccessProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String internalToken;

    public PodForwarder(WorkspaceRoutingCache routingCache,
                        WorkspaceAccessProperties properties,
                        ObjectMapper objectMapper,
                        @Value("${vance.internal.token:}") String internalToken) {
        this.routingCache = routingCache;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    /** Forward GET to the owner pod's internal endpoint and parse the JSON body. */
    public <T> T getJson(ProjectPodKey key, String pathAndQuery, Class<T> responseType) {
        String body = doGet(key, pathAndQuery);
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse response from owner pod", e);
        }
    }

    /** Same as {@link #getJson(ProjectPodKey, String, Class)} but for generic types. */
    public <T> T getJson(ProjectPodKey key, String pathAndQuery, TypeReference<T> responseType) {
        String body = doGet(key, pathAndQuery);
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse response from owner pod", e);
        }
    }

    private String doGet(ProjectPodKey key, String pathAndQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String podEndpoint = resolveOrThrow(key, attempt);
            URI uri = URI.create("http://" + podEndpoint + pathAndQuery);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getReadTimeout())
                    .header(InternalAccessFilter.HEADER_INTERNAL_TOKEN, internalToken)
                    .GET()
                    .build();
            try {
                HttpResponse<String> resp = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                throw mapInternalStatus(resp.statusCode(), resp.body());
            } catch (ConnectException | HttpTimeoutException e) {
                routingCache.invalidate(key);
                if (attempt == 1) {
                    log.warn("Pod-forwarder gave up after retry for {} {}: {}",
                            key.tenantId(), key.projectName(), e.toString());
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Owner pod unreachable", e);
                }
                log.debug("Pod-forwarder connect failed (attempt {}); retrying after cache refresh: {}",
                        attempt + 1, e.toString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Pod-forwarder I/O error", e);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Pod-forwarder exhausted retries");
    }

    private String resolveOrThrow(ProjectPodKey key, int attempt) {
        Optional<String> ip = attempt == 0 ? routingCache.lookup(key) : routingCache.refresh(key);
        return ip.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Project '" + key.tenantId() + "/" + key.projectName()
                        + "' is not claimed by any pod yet"));
    }

    private static ResponseStatusException mapInternalStatus(int code, String body) {
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new ResponseStatusException(status, body == null ? status.getReasonPhrase() : body);
    }
}
