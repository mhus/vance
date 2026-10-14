package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.shared.lease.LeaseHandler;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lease handler for {@code mcp} sessions: a revoked lease closes the
 * pool connection it belongs to. The pool key is the holder's
 * {@code holderRef}; closing runs the connection's guard
 * ({@link McpLeaseSupport}), whose {@code onSessionClose} releases the
 * (already revoked) lease idempotently, and whose transport
 * {@code close()} terminates the server session.
 *
 * <p>{@code podBound} is decided per connection from the transport at
 * acquire time (stdio: subprocess dies with the pod; HTTP: the remote
 * session needs the DELETE with the session id from the payload).
 */
@Component
@Slf4j
public class McpLeaseHandler implements LeaseHandler {

    public static final String TYPE = "mcp";

    private final ObjectProvider<McpToolPackFactory> factory;

    public McpLeaseHandler(ObjectProvider<McpToolPackFactory> factory) {
        this.factory = factory;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void onRevoked(LeaseHolderInfo info) {
        String connectionKey = info.holderRef();
        McpToolPackFactory packFactory = factory.getIfAvailable();
        if (packFactory == null) {
            log.warn("mcp lease revoke: factory not available — connection {} left to expire", connectionKey);
            return;
        }
        packFactory.revokeConnection(connectionKey);
    }
}
