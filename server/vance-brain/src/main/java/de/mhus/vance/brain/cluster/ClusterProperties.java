package de.mhus.vance.brain.cluster;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.cluster.*} — controls the brain-pod cluster registry
 * behaviour. Defaults match the user-facing intent: one cluster
 * called {@code "default"}, a heartbeat per minute, and a stale
 * window twice as wide so a single missed beat doesn't flap pods.
 *
 * <p>This class holds the <em>values</em>. How the several time windows in it
 * relate to each other — which one bounds which, and which two are
 * deliberately independent — is stated in one place,
 * {@link ClusterTimeWindows}, and that is where a reader should go before
 * changing any duration here.
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
     *
     * <p>Independent of {@link Lease#ttl} on purpose — "is the pod alive" and
     * "does the pod own this project" are two questions, see
     * {@link ClusterTimeWindows}. Routing applies both.
     */
    private Duration staleAfter = Duration.ofMinutes(2);

    /**
     * Max number of times the registration retries on a node-name
     * collision before giving up with {@code BrainPodService.NodeNameTakenException}.
     * With ~123k two-word combinations the first attempt almost always
     * wins; this exists for paranoia and tests.
     */
    private int registrationMaxRetries = 5;

    /**
     * What this pod is, as flat key/value pairs a project's
     * {@code placementSelector} can require — {@code vance.cluster.labels.gpu=true}.
     *
     * <p>Seed only: these values are written when the pod's registry row is
     * created and the heartbeat never republishes them, so a runtime write
     * through {@code PATCH /internal/cluster/pods/{podId}/placement} survives.
     * A restart returns to the seed, because the row is per JVM.
     *
     * <p>Keys must match {@code [A-Za-z0-9_-]{1,64}} — a dot is a Mongo path
     * separator, so {@code vance.cluster.labels.eu.region} is rejected at boot
     * rather than silently stored under a key no selector can name.
     */
    private Map<String, String> labels = new LinkedHashMap<>();

    /**
     * {@code true} makes this pod refuse projects that have no selector,
     * inverting the "an empty selector matches every pod" default here only.
     *
     * <p>Two boot patterns follow from this, and the choice is a deployment
     * question: <b>open</b> (default, {@code false}, no labels — takes
     * anything: homogeneous cluster, single-pod install, dev) and
     * <b>controlled</b> ({@code true}, no labels — takes nothing until an
     * external controller labels it).
     *
     * <p>The open default is forced rather than preferred: with {@code true}
     * cluster-wide, an unconfigured cluster would place nothing at all and
     * every project would sit unschedulable waiting for a controller that does
     * not exist.
     */
    private boolean exclusive = false;

    private Resources resources = new Resources();
    private Master master = new Master();
    private Locator locator = new Locator();
    private Cleanup cleanup = new Cleanup();
    private Lease lease = new Lease();
    private SelfPull selfPull = new SelfPull();

    /**
     * {@code vance.cluster.self-pull.*} — <b>when</b> this pod goes looking for
     * projects that need an owner, as opposed to waiting to be assigned one.
     *
     * <p><b>How much</b> it takes per pass stays
     * {@link Resources#getStartupScore()}; that value is also published in the
     * pod's registry row, so splitting it in two would create a second truth.
     * The two knobs answer different questions, and both are worth having:
     * {@code startupScore: 0} says "I have no budget", the switches here say
     * "do not look".
     *
     * <p>Env forms (Spring relaxed binding turns {@code .} and {@code -} into
     * {@code _}): {@code VANCE_CLUSTER_SELF_PULL_BOOT},
     * {@code VANCE_CLUSTER_SELF_PULL_SCHEDULED},
     * {@code VANCE_CLUSTER_SELF_PULL_INTERVAL}.
     */
    @Data
    public static class SelfPull {

        /**
         * Pull once when this pod comes up. <b>Default on</b>, and that is not a
         * preference: a single-pod restart and a k8s rolling restart are the
         * same event — a pod comes up while projects have just lost their holder
         * — and without this pass they stay dark until the next distributor
         * tick, or forever when the master role is off.
         *
         * <p>{@code false} is the "wait to be assigned" pod: it comes up empty
         * and takes nothing on its own initiative. The counterpart to
         * {@link ClusterProperties#exclusive}, which says "do not send me
         * projects I am not labelled for" — this one says "do not take any
         * yourself".
         */
        private boolean boot = true;

        /**
         * Additionally pull on {@link #interval}. <b>Default off</b>, and this
         * one <em>is</em> a judgement: a self-pull can only ask "may I, and do I
         * have room", never "who should get this" — it knows nothing about the
         * other pods. Run continuously on every pod it converges to "the
         * emptiest pod takes everything up to its cap" and makes the
         * distributor's load balancing decorative, i.e. it is a second
         * continuously running placement authority whose rule contradicts the
         * first one.
         *
         * <p>What it buys, precisely: recovery of projects whose only reason to
         * run is waiting background work (schedulers, hooks — the derived
         * {@code ownerRequired}) after a peer pod died, in a cluster where the
         * master role is off. Everything else has somebody asking for it.
         */
        private boolean scheduled = false;

        /**
         * Cadence of the periodic pull. Also the initial delay, so a fresh pod
         * does not run boot and periodic back to back.
         *
         * <p>Read by {@code @Scheduled} at context startup, so it takes effect
         * on restart only — and it is read even when {@link #scheduled} is off,
         * which is why it must stay a valid positive duration rather than
         * doubling as the off switch (Spring rejects a zero {@code fixedDelay}).
         */
        private Duration interval = Duration.ofMinutes(5);
    }

    @Data
    public static class Lease {
        /**
         * How long a project ownership lease stays valid without renewal.
         * Past this, any pod may take the project over — that is the whole
         * crash recovery mechanism, and the reason nothing has to be cleaned
         * up when a pod dies.
         *
         * <p>Must be comfortably larger than {@link #renewInterval}: the
         * holder has to survive a GC pause or a Mongo hiccup without losing
         * projects it is still serving. Same 5× rule of thumb as the
         * cluster-master lease.
         *
         * <p>It is read-side policy, not baked into the stored data — raising
         * it takes effect immediately instead of waiting for leases to roll
         * over.
         *
         * <p><b>Not</b> an upper bound on the other windows. It is longer than
         * {@link ClusterProperties#staleAfter} by default and may stay that
         * way; what follows from that is spelled out in
         * {@link ClusterTimeWindows}.
         */
        private Duration ttl = Duration.ofMinutes(5);

        /** Spacing of the renewal tick — one {@code updateMulti} per beat. */
        private Duration renewInterval = Duration.ofMinutes(1);
    }

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
