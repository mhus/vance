package de.mhus.vance.brain.cluster;

/**
 * Pod-to-pod call to dispatch a {@code bring} on a remote brain.
 * Implementations sign the request with the {@code _vance-cluster}
 * service-account JWT (see
 * {@code specification/cluster-project-management.md} §7).
 *
 * <p>One responsibility: tell a known target pod to bring a specific project
 * locally. Which pod that is has already been decided by
 * {@code ProjectPlacementService} — there used to be a second method here
 * ({@code requestSpawn}) that asked the master pod to decide instead, and it
 * lost its purpose when every pod became able to compute the same decision
 * from the same pod list.
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
     * POST {@code /internal/cluster/release} to {@code endpoint} — the holding
     * pod stops the project's engines and gives up the lease (a closed
     * project's workspace folder is disposed as well). Used by an archive that lands on a pod
     * other than the project's home: only the holder can tear down what runs
     * in its process. With {@code suspendSessions} the holder first interrupts
     * and suspends the project's open sessions on its lanes.
     *
     * @return {@code false} when the remote pod answers that it does not hold
     *     the project ({@code 409})
     * @throws ClusterBringException on transport or any other remote error
     */
    boolean requestRelease(String endpoint, String tenantId, String projectName, boolean suspendSessions);

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
