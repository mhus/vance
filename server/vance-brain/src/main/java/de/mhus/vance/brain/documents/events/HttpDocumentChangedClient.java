package de.mhus.vance.brain.documents.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.mhus.vance.shared.document.DocumentChangedEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pod-to-pod HTTP transport for document-change refreshes. Mirrors the
 * pattern in {@link de.mhus.vance.brain.cluster.HttpClusterBringClient}:
 * authenticated by the shared {@code X-Vance-Internal-Token} (enforced on
 * the receiving side by {@code InternalAccessFilter}) and aimed at the
 * existing {@code /internal/} path prefix.
 *
 * <p>Single endpoint: {@code POST /internal/document/changed} with a list
 * of events as the body. The receiving controller publishes each one as a
 * local {@link RoutedDocumentChangedEvent}; we treat any 2xx as success
 * and any other status as a transient failure (no retry, see Dispatcher).
 */
@Component
@Slf4j
public class HttpDocumentChangedClient {

    private static final String HEADER = "X-Vance-Internal-Token";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final String PATH = "/internal/document/changed";

    private final String internalToken;
    private final RestClient client;

    public HttpDocumentChangedClient(@Value("${vance.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HEADER, internalToken)
                .build();
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("HttpDocumentChangedClient: vance.internal.token is empty — peers will reject our calls");
        }
    }

    /**
     * POST a batch of events to {@code endpoint}'s
     * {@code /internal/document/changed}. Throws {@link RuntimeException}
     * on any failure (transport, non-2xx). Caller (the dispatcher) treats
     * failures as drop-and-warn — no retry.
     */
    public void postBatch(String endpoint, List<DocumentChangedEvent> events) {
        if (events == null || events.isEmpty()) return;
        String url = baseUrl(endpoint) + PATH;
        BatchRequest body = new BatchRequest(toWire(events));
        client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, response) -> {
                    throw new IllegalStateException(
                            "Remote refresh rejected by '" + endpoint
                                    + "': status=" + response.getStatusCode());
                })
                .toBodilessEntity();
    }

    private static String baseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Empty endpoint");
        }
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        return "http://" + endpoint;
    }

    private static List<WireEvent> toWire(List<DocumentChangedEvent> events) {
        List<WireEvent> out = new ArrayList<>(events.size());
        for (DocumentChangedEvent e : events) {
            String op = switch (e) {
                case DocumentChangedEvent.Upserted ignored -> "UPSERT";
                case DocumentChangedEvent.Deleted ignored -> "DELETE";
            };
            out.add(new WireEvent(op, e.tenantId(), e.projectId(), e.path(), e.documentId()));
        }
        return out;
    }

    /** Wire envelope. Stable, version-tolerant on the receiver side. */
    public record BatchRequest(@JsonProperty("events") List<WireEvent> events) {}

    public record WireEvent(
            @JsonProperty("op") String op,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("projectId") String projectId,
            @JsonProperty("path") String path,
            @JsonProperty("documentId") String documentId) {}
}
