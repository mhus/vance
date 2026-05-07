package de.mhus.vance.brain.cluster;

import java.time.Duration;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.cluster.*} — controls the brain-pod cluster registry
 * behaviour. Defaults match the user-facing intent: one cluster
 * called {@code "default"}, a heartbeat per minute, and a stale
 * window twice as wide so a single missed beat doesn't flap pods.
 */
@Data
@ConfigurationProperties(prefix = "vance.cluster")
public class ClusterProperties {

    /**
     * Cluster identifier. Pods only see other pods within the same
     * {@code clusterId}. Lets you point dev / staging / prod at the
     * same Mongo without cross-environment pollution.
     */
    private String id = "default";

    /**
     * Optional explicit human-friendly name for this pod. Leave blank
     * to let {@link ClusterNodeNameGenerator} pick a two-word random
     * name from the bundled dictionary.
     */
    private @Nullable String nodeName;

    /** Spacing of the heartbeat tick. */
    private Duration heartbeatInterval = Duration.ofMinutes(1);

    /**
     * Observer-side staleness window. A pod whose
     * {@code lastHeartbeatAt} is older than this is treated as gone by
     * {@code BrainPodService.isStale}. Default is 2× the heartbeat
     * interval so a single missed tick does not flap pods.
     */
    private Duration staleAfter = Duration.ofMinutes(2);

    /**
     * Max number of times the registration retries on a node-name
     * collision before giving up with {@code BrainPodService.NodeNameTakenException}.
     * With ~123k two-word combinations the first attempt almost always
     * wins; this exists for paranoia and tests.
     */
    private int registrationMaxRetries = 5;
}
