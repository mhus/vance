package de.mhus.vance.brain.cluster;

import de.mhus.vance.api.cluster.PingResponse;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated heartbeat endpoint. Returns this pod's own row from
 * {@code brain_pods} plus the server clock — that's all an admin tool
 * needs to confirm the JWT round-trip and which physical pod served
 * the call.
 *
 * <p>The route sits at {@code /brain/{tenant}/admin/ping} so the
 * existing {@code BrainAccessFilter} requires a valid bearer token.
 * No additional authorisation check (any user with a valid token may
 * ping) — the response carries no tenant-scoped data.
 *
 * <p>If registration hasn't happened yet (which would be a transient
 * window during boot) the controller falls back to whatever it knows
 * about itself rather than 503-ing the caller.
 */
@RestController
@RequiredArgsConstructor
public class PingController {

    private final ClusterService clusterService;

    @GetMapping("/brain/{tenant}/admin/ping")
    public PingResponse ping(@PathVariable("tenant") String tenant) {
        Optional<BrainPodDocument> self = clusterService.selfPod();
        Instant now = Instant.now();

        return self.map(doc -> PingResponse.builder()
                        .podId(doc.getPodId())
                        .nodeName(doc.getNodeName())
                        .clusterId(doc.getClusterId())
                        .endpoint(doc.getEndpoint())
                        .status(doc.getStatus() == null ? "" : doc.getStatus().name())
                        .version(doc.getVersion())
                        .bootedAt(doc.getBootedAt())
                        .lastHeartbeatAt(doc.getLastHeartbeatAt())
                        .serverTime(now)
                        .build())
                .orElseGet(() -> PingResponse.builder()
                        .podId(clusterService.selfPodId())
                        .nodeName(clusterService.selfNodeName())
                        .clusterId(clusterService.selfClusterId())
                        .endpoint("")
                        .status("UNREGISTERED")
                        .serverTime(now)
                        .build());
    }
}
