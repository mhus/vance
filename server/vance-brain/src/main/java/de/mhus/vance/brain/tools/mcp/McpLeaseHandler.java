package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHandler;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lease handler for {@code mcp} sessions. Two cases:
 *
 * <ul>
 *   <li>The session lives in this pod's pool and still holds the revoked
 *       lease → close the pool connection. Closing runs the connection's
 *       guard ({@link McpLeaseSupport}), whose {@code onSessionClose}
 *       releases the (already revoked) lease idempotently, and the
 *       transport's {@code close()} terminates the server session.</li>
 *   <li>The holder pod is gone and the resource is not pod-bound (HTTP)
 *       → the remote session still runs; DELETE it with the session id
 *       from the lease payload ({@link McpToolPackFactory#terminateRemoteSession}).</li>
 * </ul>
 *
 * <p>A revocation for a session that has meanwhile been replaced (new
 * lease, new token) is stale and closes nothing.
 */
@Component
@Slf4j
public class McpLeaseHandler implements LeaseHandler {

    public static final String TYPE = "mcp";

    private final ObjectProvider<McpToolPackFactory> factory;
    private final ClusterService cluster;

    public McpLeaseHandler(ObjectProvider<McpToolPackFactory> factory, ClusterService cluster) {
        this.factory = factory;
        this.cluster = cluster;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void onRevoked(LeaseHolderInfo info) {
        McpToolPackFactory packFactory = factory.getIfAvailable();
        if (packFactory == null) {
            log.warn("mcp lease revoke: factory not available — connection {} left to expire", info.holderRef());
            return;
        }
        if (packFactory.revokeConnection(info.holderRef(), info.token())) {
            return;
        }
        // Not (or no longer) in this pod's pool. On the holder's own pod the
        // session closed together with its connection; a pod-bound (stdio)
        // resource died with its pod. Only a foreign pod cleaning up after a
        // dead holder still has a remote session to end.
        if (info.podBound() || info.pod().equals(cluster.selfPodId())) {
            return;
        }
        packFactory.terminateRemoteSession(info);
    }
}
