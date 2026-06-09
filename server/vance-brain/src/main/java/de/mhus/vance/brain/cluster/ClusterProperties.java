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

    private Resources resources = new Resources();
    private Master master = new Master();
    private Locator locator = new Locator();
    private Cleanup cleanup = new Cleanup();

    @Data
    public static class Resources {
        /**
         * How many project-score units this pod claims at boot via the
         * Boot-Self-Pull (see
         * {@code specification/cluster-project-management.md} §5.1).
         * The pull picks up PERMANENT-orphans until this budget is
         * reached (plus a buffer for the last candidate).
         */
        private int startupScore = 100;

        /**
         * Hard cap the Cluster-Master Distributor respects when picking
         * a pod to receive an orphaned project. The local pod ignores
         * the cap on direct bring — overrun is acceptable.
         */
        private int maxScore = 10000;
    }

    @Data
    public static class Master {
        /**
         * {@code false} disables the Cluster-Master role cluster-wide on
         * this pod. Direct spawn falls back to local-bring (with a
         * warning) and orphans stay unplaced until something asks for
         * them via the {@code ProjectLocator}.
         */
        private boolean enabled = true;

        /** How long a lease is valid once granted. */
        private Duration leaseDuration = Duration.ofMinutes(5);

        /** Spacing of the election/renew tick on every pod. */
        private Duration electionInterval = Duration.ofSeconds(30);

        /** Spacing of the distributor tick on the master pod only. */
        private Duration distributorInterval = Duration.ofSeconds(60);

        /**
         * Renew the lease this far before its expiry — gives some
         * headroom for GC pauses or short Mongo hiccups. Should be
         * {@code >= electionInterval} so a single missed tick still
         * leaves time to renew.
         */
        private Duration renewSafetyMargin = Duration.ofMinutes(2);

        /** Hard cap on permanent-orphans the distributor places per tick. */
        private int maxPerTick = 50;
    }

    @Data
    public static class Locator {
        /**
         * Max time {@code ProjectLocator.locate(..., autoStart=true)}
         * blocks waiting for a spawn to finish before throwing.
         */
        private Duration autoStartTimeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Cleanup {
        /**
         * Spacing of the cleanup tick. Runs on every pod but no-ops
         * unless the local pod currently holds the Cluster-Master lease.
         */
        private Duration interval = Duration.ofMinutes(10);

        /**
         * Hard-delete a {@code brain_pods} row whose {@code lastHeartbeatAt}
         * is older than this. Independent of (and much larger than)
         * {@link ClusterProperties#staleAfter} — the short stale window
         * gates routing decisions, this long one gates row purging.
         *
         * <p>Status is intentionally ignored: a crashed pod stays at
         * {@code RUNNING} but stops beating, and we want those purged
         * just as much as cleanly STOPPED rows.
         */
        private Duration after = Duration.ofHours(1);
    }
}
