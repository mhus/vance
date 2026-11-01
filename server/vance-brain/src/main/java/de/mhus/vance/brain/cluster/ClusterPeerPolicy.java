package de.mhus.vance.brain.cluster;

/**
 * Whether the other pods in the registry count at all.
 *
 * <p>The single-node edition ({@link SingleNodePeerPolicy}) treats every pod
 * but itself as stale: a restart gets a new pod id while the old row is still
 * inside its heartbeat window, and the old instance must neither block the new
 * one nor keep its projects. The cloud edition counts peers by their heartbeat.
 *
 * <p>Read only by {@link ClusterService}; it is the one place that judges a
 * foreign pod, so no caller needs to know which edition it runs in.
 */
public interface ClusterPeerPolicy {

    /** {@code true} when pods other than this one are taken into account by liveness. */
    boolean countsPeers();
}
