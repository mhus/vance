package de.mhus.vance.brain.tools.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Who ends an MCP session when its lease is revoked: the pool when it
 * still holds the session under that token, the reaper pod (remote
 * DELETE) when the holder pod is gone and the session is not pod-bound,
 * nobody otherwise.
 */
class McpLeaseHandlerTest {

    private static final String SELF = "pod-self";

    private McpToolPackFactory factory;
    private McpLeaseHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        factory = mock(McpToolPackFactory.class);
        ObjectProvider<McpToolPackFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        ClusterService cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        handler = new McpLeaseHandler(provider, cluster);
    }

    private static LeaseHolderInfo info(String pod, boolean podBound) {
        return new LeaseHolderInfo(
                "rl-1",
                McpLeaseHandler.TYPE,
                "browser",
                "doc-1|acme|alice",
                "acme",
                "proj",
                "alice",
                pod,
                podBound,
                Map.of("sessionId", "sess-1", "tool", "browser"));
    }

    @Test
    void sessionInThisPool_isClosed_noRemoteDelete() {
        when(factory.revokeConnection("doc-1|acme|alice", "rl-1")).thenReturn(true);

        handler.onRevoked(info("pod-gone", false));

        verify(factory, never()).terminateRemoteSession(any());
    }

    @Test
    void deadForeignHolder_httpSession_isTerminatedRemotely() {
        LeaseHolderInfo info = info("pod-gone", false);

        handler.onRevoked(info);

        verify(factory).terminateRemoteSession(info);
    }

    @Test
    void deadForeignHolder_podBoundSession_diedWithThePod() {
        handler.onRevoked(info("pod-gone", true));

        verify(factory, never()).terminateRemoteSession(any());
    }

    @Test
    void ownPod_sessionAlreadyReplaced_staleRevocationClosesNothing() {
        // The pool holds the key under a newer lease: revokeConnection says
        // no; the old session closed with its connection — nothing remote.
        handler.onRevoked(info(SELF, false));

        verify(factory, never()).terminateRemoteSession(any());
    }
}
