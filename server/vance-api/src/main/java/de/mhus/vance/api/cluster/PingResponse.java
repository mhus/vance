package de.mhus.vance.api.cluster;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response of {@code GET /brain/{tenant}/admin/ping}. Carries enough
 * pod-self-identification for an admin tool ({@code cluster ping}) to
 * confirm both the JWT round-trip and which physical pod actually
 * served the request — useful when verifying load balancer routing or
 * cross-pod forwarding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("cluster")
public class PingResponse {

    private String podId;
    private String nodeName;
    private String clusterId;

    /** {@code host:port} the pod advertises to peers. */
    private String endpoint;

    /** Pod-self-reported lifecycle phase ({@code STARTING / READY / DRAINING / STOPPED}). */
    private String status;

    private @Nullable String version;

    private @Nullable Instant bootedAt;
    private @Nullable Instant lastHeartbeatAt;

    /** Server clock at the moment the ping was answered — useful for skew checks. */
    private Instant serverTime;
}
