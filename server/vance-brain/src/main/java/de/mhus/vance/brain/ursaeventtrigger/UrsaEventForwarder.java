package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.ursaevents.EventTriggerResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hands an event trigger to the pod that holds the project.
 *
 * <p>An event spawns work on the project's lane, so it has to run where the
 * project actually is. Before ownership was a lease that question had no
 * reliable answer and the trigger simply ran on whichever pod took the HTTP
 * call; now the lease names the pod, so the call can go there.
 *
 * <p><b>One hop, enforced by a header.</b> The forwarded request carries
 * {@link #FORWARDED_HEADER}, and a pod that sees it runs the trigger locally
 * instead of resolving the owner again. Without that, a lease that changes
 * hands mid-flight could bounce a request between two pods.
 *
 * <p>Targets the ordinary public event endpoint rather than an internal one:
 * the bearer token travels with the request and is re-checked on arrival, so
 * the receiving pod authenticates the trigger itself instead of trusting the
 * hop. The internal token is sent too, but as a statement about the hop, not
 * as the authorisation for the event.
 */
@Component
@Slf4j
public class UrsaEventForwarder {

    /** Present on a request that was already routed once. Never set by callers. */
    public static final String FORWARDED_HEADER = "X-Vance-Event-Forwarded";

    private static final String INTERNAL_TOKEN_HEADER = "X-Vance-Internal-Token";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /**
     * Generous: the owner pod may still be finishing its bring when the request
     * arrives, and the whole point of forwarding is to wait for the project
     * rather than answer from a pod that does not have it.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient.Builder restClientBuilder;

    public UrsaEventForwarder(
            @org.springframework.beans.factory.annotation.Value("${vance.internal.token:}")
            String internalToken) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClientBuilder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(FORWARDED_HEADER, "1")
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken == null ? "" : internalToken);
    }

    /**
     * Sends the trigger to {@code endpoint} and returns what that pod
     * answered. A remote rejection is passed through with its own status —
     * a 401 from the owner is the caller's 401, not a proxy failure.
     */
    public EventTriggerResponse forward(
            String endpoint,
            String tenantId,
            String projectId,
            String eventName,
            String httpMethod,
            @Nullable String bearerToken,
            @Nullable Object payload) {
        String url = baseUrl(endpoint)
                + "/brain/" + tenantId + "/event/" + projectId + "/" + eventName;
        try {
            RestClient client = restClientBuilder.build();
            RestClient.RequestHeadersSpec<?> spec;
            if ("GET".equalsIgnoreCase(httpMethod)) {
                spec = client.get().uri(url);
            } else {
                spec = client.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload == null ? java.util.Map.of() : payload);
            }
            if (bearerToken != null && !bearerToken.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + bearerToken);
            }
            EventTriggerResponse response = spec
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ResponseStatusException(res.getStatusCode(),
                                "Event '" + eventName + "' rejected by the owning pod");
                    })
                    .body(EventTriggerResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Owning pod returned an empty response for event '" + eventName + "'");
            }
            return response;
        } catch (RestClientException e) {
            // The pod holds the project but did not answer. Running the event
            // here instead would put the work on the wrong lane, so this is a
            // failure, not a fallback.
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not reach the pod holding '" + tenantId + "/" + projectId
                            + "' for event '" + eventName + "'", e);
        }
    }

    private static String baseUrl(String endpoint) {
        return endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint
                : "http://" + endpoint;
    }
}
