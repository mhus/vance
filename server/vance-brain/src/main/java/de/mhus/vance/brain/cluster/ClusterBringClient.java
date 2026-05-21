package de.mhus.vance.brain.cluster;

/**
 * Pod-to-pod call to dispatch a {@code bring} on a remote brain.
 * Implementations sign the request with the {@code _vance-cluster}
 * service-account JWT (see
 * {@code specification/cluster-project-management.md} §7).
 *
 * <p>Two responsibilities, both used by the Cluster-Master Distributor
 * and the Direct-Spawn path:
 * <ul>
 *   <li>{@link #requestBring} — tell a known target pod to bring a
 *       specific project locally.</li>
 *   <li>{@link #requestSpawn} — ask the master pod to pick a target and
 *       dispatch the bring itself.</li>
 * </ul>
 */
public interface ClusterBringClient {

    /**
     * POST {@code /cluster/internal/bring} to {@code endpoint}. The remote
     * pod runs {@code lifecycleService.bring} blind — no score check
     * there. Returns the {@code homeNode} the project was claimed by.
     *
     * @throws ClusterBringException on transport or remote error
     */
    String requestBring(String endpoint, String tenantId, String projectName);

    /**
     * POST {@code /cluster/master/spawn} to the master pod. Master picks
     * a target and forwards via {@link #requestBring}. Synchronous.
     *
     * @return the {@code SpawnResult} with the chosen node-name and
     *         endpoint
     * @throws ClusterBringException on transport, no-master, or cluster-full
     */
    SpawnResult requestSpawn(String masterEndpoint, String tenantId, String projectName);

    record SpawnResult(String nodeName, String endpoint) {}

    /** Wraps transport + remote-side failures for the callers to translate. */
    class ClusterBringException extends RuntimeException {
        public ClusterBringException(String message) {
            super(message);
        }

        public ClusterBringException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
