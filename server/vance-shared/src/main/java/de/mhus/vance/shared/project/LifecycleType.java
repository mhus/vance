package de.mhus.vance.shared.project;

/**
 * Tells the cluster-wide spawn logic whether a project must be actively
 * kept alive on a pod (and how strictly). See
 * {@code specification/cluster-project-management.md} §2.
 *
 * <p>{@link #HOMELESS} is hard-wired to {@link ProjectKind#SYSTEM} (the
 * {@code _vance} / {@code _user_<login>} projects) and immutable.
 * {@link #EPHEMERAL} and {@link #PERMANENT} are user-chosen on
 * {@link ProjectKind#NORMAL} projects and may be switched at runtime
 * via {@code ProjectService.setLifecycleType}.
 */
public enum LifecycleType {

    /**
     * No pod-affinity. The {@code homeNode} field stays {@code null}
     * forever; every pod that touches such a project handles it locally
     * via the existing "podless" code paths. Set automatically for every
     * {@link ProjectKind#SYSTEM} project at create time.
     */
    HOMELESS,

    /**
     * Pod-affine, but only brought online on demand: a {@code ProjectLocator}
     * lookup with {@code autoStart=true}, a session bind, or a direct
     * client request triggers the bring. After a pod death the project
     * lies dormant until something requests it again — no Boot-Self-Pull,
     * no Master-Distributor activity.
     */
    EPHEMERAL,

    /**
     * Pod-affine and actively kept alive. Boot-Self-Pull greedily claims
     * such projects up to {@code resourcesStartupScore}; the Cluster-Master
     * Distributor re-places orphans on healthy pods. Scheduler triggers
     * and event-driven workflows that must not silently stop firing
     * belong here.
     */
    PERMANENT
}
