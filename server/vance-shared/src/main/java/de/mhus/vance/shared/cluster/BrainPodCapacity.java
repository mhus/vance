package de.mhus.vance.shared.cluster;

/**
 * The one place that answers "how much room does this pod have".
 *
 * <p>Pure computation, no I/O — the caller already holds the document. Same
 * shape and same hard rule as {@link PodSelector} and
 * {@code ProjectOwnership}: <b>{@code resourcesMaxScore} and
 * {@code resourcesMaxScoreOverride} are read here and nowhere else.</b>
 *
 * <p>The rule exists because a forgotten reader fails <em>silently</em>: it
 * would compare against the configured cap and ignore a runtime override, so
 * the pod keeps accepting work and the wrong placement is explained by a number
 * nobody can see. There are only three readers (load sorting, local headroom,
 * the fit loop) and all three matter.
 *
 * <h2>Two layers, and why</h2>
 * {@code resourcesMaxScore} is what the pod was <em>configured</em> with — the
 * heartbeat republishes it every beat, which keeps a hand-edited row
 * self-healing. {@code resourcesMaxScoreOverride} is what somebody set at
 * runtime, and the heartbeat never touches it.
 *
 * <p>Kept as two fields rather than one overwritten value because a single
 * field cannot say that it was overridden. A capacity number that silently
 * returns to its configured value — which it does on the next re-registration,
 * see below — has to be distinguishable from one that was configured that way,
 * or the question "was this pod throttled or is it just small" is unanswerable
 * after the fact.
 *
 * <p><b>An override does not survive re-registration.</b> The row is per JVM;
 * if it is purged (admin prune, or the cleanup tick after a long heartbeat
 * outage) the pod builds a fresh document from its configuration and the
 * override is gone. That is deliberate and the reason the override is
 * <em>displayed</em> as temporary: the durable fix for a wrong cap is the
 * configuration, and this is the stopgap until the next deploy. Making it
 * outlive a restart would need a pod identity that is stable across processes,
 * and only a pinned {@code vance.cluster.node-name} provides one — a promise
 * that would hold for half of all deployments is worse than none.
 */
public final class BrainPodCapacity {

    private BrainPodCapacity() {}

    /**
     * The cap placement actually compares against: the runtime override when
     * one is set, otherwise the configured value.
     *
     * <p>Clamped to at least 1. An unset {@code resourcesMaxScore} on an old or
     * hand-written row would otherwise mean "fits nothing", which reads as a
     * full pod and is the harder failure to diagnose of the two.
     */
    public static int effectiveMaxScore(BrainPodDocument pod) {
        Integer override = pod.getResourcesMaxScoreOverride();
        int configured = pod.getResourcesMaxScore();
        return Math.max(1, override != null ? override : configured);
    }

    /**
     * Score units still free before {@link #effectiveMaxScore}. May be negative
     * when the pod is overbooked, which is a legitimate state — the cap has
     * always been best-effort, and several paths take a project without asking.
     */
    public static int headroom(BrainPodDocument pod) {
        return effectiveMaxScore(pod) - pod.getResourcesCurrentScore();
    }

    /** Load as a fraction of the effective cap — the placement sort key. */
    public static double loadFraction(BrainPodDocument pod) {
        return ((double) pod.getResourcesCurrentScore()) / effectiveMaxScore(pod);
    }

    /** {@code true} when a runtime override is in effect. For display. */
    public static boolean isOverridden(BrainPodDocument pod) {
        return pod.getResourcesMaxScoreOverride() != null;
    }
}
