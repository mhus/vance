package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One held resource lease, as the Insights Leases tab shows it.
 * {@code token} is the handle for a revoke.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LeaseHolderInsightsDto {

    /** Holder token ({@code rl-<uuid>}). */
    private String token;

    /** {@code PENDING} while the funnel runs, {@code HELD} afterwards. */
    private String state;

    /** Lease type — selects the cleanup handler ({@code tex}, {@code mcp}, …). */
    private String type;

    /** Resource key of the lease definition. */
    private String resource;

    /** Consumer-specific instance key. */
    private String holderRef;

    private @Nullable String projectId;

    private @Nullable String userId;

    /** Pod id of the acquiring pod. */
    private String pod;

    /** Node name of that pod, {@code null} when the pod is no longer registered. */
    private @Nullable String podNodeName;

    /** Whether the holder pod is alive by the cluster's own criterion. */
    private boolean podAlive;

    /** Whether the resource dies with the holder pod. */
    private boolean podBound;

    /** Idle TTL of the lease — past {@code lastSeenAt + ttlSeconds} the reaper takes it. */
    private long ttlSeconds;

    private @Nullable Instant acquiredAt;

    /** Last check-at-use touch (Mongo server time). */
    private @Nullable Instant lastSeenAt;

    /** Consumer cleanup data, values rendered as text. */
    @Builder.Default
    private Map<String, String> payload = new LinkedHashMap<>();
}
