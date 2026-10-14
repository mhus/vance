package de.mhus.vance.brain.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.Lease;
import de.mhus.vance.shared.lease.LeaseDefinition;
import de.mhus.vance.shared.lease.LeaseLostException;
import de.mhus.vance.shared.lease.LeaseRequest;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.mcp.McpSessionRevokedException;
import de.mhus.vance.toolpack.mcp.SessionGuard;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The lease-backed {@link SessionGuard} of {@link McpLeaseSupport}: what
 * an inline definition may say, how the request is scoped, and how the
 * guard maps a lost lease and exposes the held token.
 */
class McpLeaseSupportTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "proj", "s", "proc", "alice");
    private static final String KEY = "doc-1|acme|alice";

    private ResourceLeaseService leaseService;
    private McpLeaseSupport support;
    private Lease lease;

    @BeforeEach
    void setUp() {
        leaseService = mock(ResourceLeaseService.class);
        ClusterService cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn("pod-1");
        support = new McpLeaseSupport(leaseService, cluster);
        lease = mock(Lease.class);
        when(lease.holderToken()).thenReturn("rl-1");
        when(leaseService.acquire(any())).thenReturn(lease);
    }

    private static ServerToolDocument doc(Object leaseParam, String transport) {
        Map<String, Object> params = new HashMap<>();
        params.put("transport", transport);
        if ("http".equals(transport)) params.put("url", "http://mcp.example/mcp");
        else params.put("command", java.util.List.of("npx", "server-x"));
        if (leaseParam != null) params.put("lease", leaseParam);
        return ServerToolDocument.builder()
                .id("doc-1")
                .name("browser")
                .tenantId("acme")
                .projectId("proj")
                .type("mcp_server")
                .parameters(params)
                .build();
    }

    private SessionGuard guard(ServerToolDocument doc) {
        return support.createGuard(doc, McpConfig.fromParameters(doc.getParameters()), CTX, KEY);
    }

    private LeaseRequest openedRequest(SessionGuard guard) {
        guard.onSessionOpen();
        ArgumentCaptor<LeaseRequest> captor = ArgumentCaptor.forClass(LeaseRequest.class);
        verify(leaseService).acquire(captor.capture());
        return captor.getValue();
    }

    @Test
    void noLeaseParameter_noGuard() {
        assertThat(guard(doc(null, "http"))).isNull();
    }

    @Test
    void namedLease_httpTransport_isNotPodBound_andScopedToTheCaller() {
        LeaseRequest request = openedRequest(guard(doc("browser", "http")));

        assertThat(request.leaseName()).isEqualTo("browser");
        assertThat(request.podBound()).isFalse();
        assertThat(request.tenantId()).isEqualTo("acme");
        assertThat(request.projectId()).isEqualTo("proj");
        assertThat(request.userId()).isEqualTo("alice");
        assertThat(request.holderRef()).isEqualTo(KEY);
        assertThat(request.payload())
                .containsEntry("tool", "browser")
                .containsEntry("endpoint", "http://mcp.example/mcp");
    }

    @Test
    void stdioTransport_isPodBound() {
        assertThat(openedRequest(guard(doc("browser", "stdio"))).podBound()).isTrue();
    }

    @Test
    void inlineLease_countsOnAResourcePrivateToTheProject() {
        LeaseRequest request =
                openedRequest(guard(doc(Map.of("name", "browser", "levels", Map.of("user", 1)), "http")));

        LeaseDefinition inline = request.inline();
        assertThat(inline).isNotNull();
        assertThat(inline.resourceKey()).isEqualTo("mcp-inline:acme/proj/browser");
        assertThat(inline.maxUser()).isEqualTo(1L);
        assertThat(inline.maxGlobal()).isNull();
    }

    @Test
    void inlineLease_mayNotTouchSharedCounters() {
        assertThatThrownBy(() ->
                        guard(doc(Map.of("name", "x", "resource", "tex2pdf", "levels", Map.of("project", 1)), "http")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named lease");
        assertThatThrownBy(() -> guard(doc(Map.of("name", "x", "levels", Map.of("global", 1000)), "http")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard(doc(Map.of("name", "x", "levels", Map.of("tenant", 5)), "http")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inlineLease_needsAValidName() {
        assertThatThrownBy(() -> guard(doc(Map.of("name", "../x", "levels", Map.of("user", 1)), "http")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid 'name'");
    }

    @Test
    void sessionId_landsInThePayload_nextToTheToolName() {
        SessionGuard guard = guard(doc("browser", "http"));
        guard.onSessionOpen();

        guard.onSessionId("sess-9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lease).updatePayload(payload.capture());
        assertThat(payload.getValue()).containsEntry("sessionId", "sess-9").containsEntry("tool", "browser");
    }

    @Test
    void lostLease_surfacesAsRevokedSession() {
        SessionGuard guard = guard(doc("browser", "http"));
        guard.onSessionOpen();
        doThrow(new LeaseLostException("idle reaped")).when(lease).check();

        assertThatThrownBy(guard::onSessionUse)
                .isInstanceOf(McpSessionRevokedException.class)
                .hasMessageContaining("idle reaped");
    }

    @Test
    void sessionToken_isTheHeldLease_untilClose() {
        SessionGuard guard = guard(doc("browser", "http"));
        assertThat(guard.sessionToken()).isNull();

        guard.onSessionOpen();
        assertThat(guard.sessionToken()).isEqualTo("rl-1");

        guard.onSessionClose();
        assertThat(guard.sessionToken()).isNull();
        verify(lease).release();
    }
}
