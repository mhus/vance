package de.mhus.vance.brain.lease;

import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pod-to-pod HTTP transport for lease revocations — the twin of
 * {@code HttpDocumentChangedClient}: authenticated by the shared
 * {@code X-Vance-Internal-Token} (enforced on the receiving side by
 * {@code InternalAccessFilter}) and aimed at the existing
 * {@code /internal/} path prefix.
 *
 * <p>Single endpoint: {@code POST /internal/lease/revoked} with the
 * {@link LeaseHolderInfo} as the body. Any 2xx is success; anything else
 * is a failure the caller ({@link ResourceLeaseSweeper}) treats as
 * "holder pod unreachable" — which routes into the dead-pod path of the
 * {@code podBound} matrix.
 */
@Component
@Slf4j
public class HttpLeaseRevokedClient {

    private static final String HEADER = "X-Vance-Internal-Token";
    private static final String PATH = "/internal/lease/revoked";

    private final RestClient client;

    public HttpLeaseRevokedClient(@Value("${vance.internal.token:}") String internalToken) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HEADER, internalToken)
                .build();
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("HttpLeaseRevokedClient: vance.internal.token is empty — peers will reject our calls");
        }
    }

    /** POSTs one revocation; throws on any failure. */
    public void post(String endpoint, LeaseHolderInfo info) {
        client.post()
                .uri(endpoint + PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(info)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                    HttpStatusCode code = res.getStatusCode();
                    throw new IllegalStateException("lease revoke POST " + PATH + " returned " + code.value());
                })
                .toBodilessEntity();
    }
}
