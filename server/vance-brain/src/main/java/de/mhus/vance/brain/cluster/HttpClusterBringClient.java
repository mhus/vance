package de.mhus.vance.brain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP {@link ClusterBringClient} that signs every request with the
 * shared {@code vance.internal.token} (the same secret
 * {@link de.mhus.vance.brain.workspace.access.InternalAccessFilter}
 * already enforces). Lives under the existing {@code /internal/} path
 * prefix — see {@code specification/cluster-project-management.md} §7.
 *
 * <p>{@code @Primary} so the {@link NoopClusterBringClient} fallback
 * is only used when this bean is intentionally absent (e.g. tests that
 * disable HTTP).
 */
@Component
@Primary
@Slf4j
public class HttpClusterBringClient implements ClusterBringClient {

    private static final String HEADER = "X-Vance-Internal-Token";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final String internalToken;
    private final RestClient.Builder restClientBuilder;

    public HttpClusterBringClient(@Value("${vance.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClientBuilder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HEADER, internalToken);
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("HttpClusterBringClient: vance.internal.token is empty — pod-to-pod calls will be rejected by peers");
        }
    }

    @Override
    public String requestBring(String endpoint, String tenantId, String projectName) {
        String url = baseUrl(endpoint) + "/internal/cluster/bring";
        BringResponse resp;
        try {
            resp = restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BringRequest(tenantId, projectName))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw new ClusterBringException(
                                "Remote bring rejected by '" + endpoint + "' for '"
                                        + tenantId + "/" + projectName
                                        + "': status=" + response.getStatusCode());
                    })
                    .body(BringResponse.class);
        } catch (RestClientException e) {
            throw new ClusterBringException(
                    "Remote bring to '" + endpoint + "' for '"
                            + tenantId + "/" + projectName + "' failed: " + e.getMessage(), e);
        }
        if (resp == null || resp.homeNode == null) {
            throw new ClusterBringException(
                    "Remote bring to '" + endpoint + "' returned empty homeNode");
        }
        return resp.homeNode;
    }

    @Override
    public SpawnResult requestSpawn(String masterEndpoint, String tenantId, String projectName) {
        String url = baseUrl(masterEndpoint) + "/internal/cluster/master/spawn";
        SpawnResponse resp;
        try {
            resp = restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SpawnRequest(tenantId, projectName))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        HttpStatusCode sc = response.getStatusCode();
                        String reason = "status=" + sc;
                        if (sc.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                            reason = "cluster full";
                        } else if (sc.value() == HttpStatus.MISDIRECTED_REQUEST.value()) {
                            reason = "not master (try resolving master endpoint again)";
                        }
                        throw new ClusterBringException(
                                "Remote spawn rejected by master '" + masterEndpoint + "' for '"
                                        + tenantId + "/" + projectName + "': " + reason);
                    })
                    .body(SpawnResponse.class);
        } catch (RestClientException e) {
            throw new ClusterBringException(
                    "Remote spawn via master '" + masterEndpoint + "' for '"
                            + tenantId + "/" + projectName + "' failed: " + e.getMessage(), e);
        }
        if (resp == null || resp.nodeName == null || resp.endpoint == null) {
            throw new ClusterBringException(
                    "Remote spawn via master '" + masterEndpoint
                            + "' returned incomplete result");
        }
        return new SpawnResult(resp.nodeName, resp.endpoint);
    }

    private static String baseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new ClusterBringException("Empty endpoint");
        }
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        return "http://" + endpoint;
    }

    /** Wire DTO for the bring endpoint — kept package-private. */
    public record BringRequest(@JsonProperty("tenantId") String tenantId,
                               @JsonProperty("projectName") String projectName) {}

    public record BringResponse(@JsonProperty("homeNode") String homeNode) {}

    public record SpawnRequest(@JsonProperty("tenantId") String tenantId,
                               @JsonProperty("projectName") String projectName) {}

    public record SpawnResponse(@JsonProperty("nodeName") String nodeName,
                                @JsonProperty("endpoint") String endpoint) {}
}
