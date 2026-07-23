package de.mhus.vance.brain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.tools.client.ClientToolChannel;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class ExecutionRouterTest {

    private ExecutionRegistryService registry;
    private ExecManager execManager;
    private ClientToolRegistry clientToolRegistry;
    private ClientToolChannel clientToolChannel;
    private ExecutionRouter router;

    @BeforeEach
    void setUp() {
        registry = new ExecutionRegistryService();
        execManager = mock(ExecManager.class);
        clientToolRegistry = new ClientToolRegistry();
        clientToolChannel = mock(ClientToolChannel.class);
        router = new ExecutionRouter(
                registry, execManager, clientToolRegistry, clientToolChannel);
    }

    @Test
    void unknownExecution_throws() {
        assertThatThrownBy(() -> router.stat("nope", "acme", null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown execution");
    }

    @Test
    void crossTenant_isRejected() {
        registry.register(brainEntry("e1", "acme", "p1"));

        assertThatThrownBy(() -> router.stat("e1", "evil-corp", null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("different tenant");
    }

    @Test
    void crossProject_isRejected() {
        registry.register(brainEntry("e1", "acme", "p1"));

        assertThatThrownBy(() -> router.stat("e1", "acme", "other-project"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void footRoute_dispatchesViaClientToolChannel() throws Exception {
        // Connect a foot client and pretend it has client_exec_stat available.
        WebSocketSession ws = mock(WebSocketSession.class);
        clientToolRegistry.register(
                "sess-1", "conn-foo", ws,
                List.of(ToolSpec.builder().name("client_exec_stat").build()));

        registry.register(footEntry("e1", "conn-foo", "acme", "proj"));

        // The router will call channel.sendInvoke and then block on the
        // pending future. Complete it from another thread.
        doAnswer(inv -> {
            String correlationId = inv.getArgument(1);
            // simulate foot reply on a worker thread
            new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                clientToolRegistry.completeInvocation(
                        correlationId,
                        Map.of("id", "e1", "status", "RUNNING"),
                        null);
            }).start();
            return null;
        }).when(clientToolChannel).sendInvoke(eq(ws), any(), eq("client_exec_stat"), any());

        Map<String, Object> result = router.stat("e1", "acme", null);

        assertThat(result).containsEntry("status", "RUNNING");
    }

    @Test
    void footRoute_withDisconnectedClient_throws() {
        registry.register(footEntry("e1", "conn-gone", "acme", "proj"));

        assertThatThrownBy(() -> router.stat("e1", "acme", null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not connected");
    }

    private ExecutionRegistryEntry brainEntry(String id, String tenantId, String projectId) {
        Instant now = Instant.now();
        return new ExecutionRegistryEntry(
                id, ExecutionOwner.Brain.INSTANCE,
                tenantId, projectId, "sess", null,
                "true", null,
                now, now, null,
                ExecutionStatus.RUNNING, null, null, null);
    }

    private ExecutionRegistryEntry footEntry(
            String id, String clientId, String tenantId, String projectId) {
        Instant now = Instant.now();
        return new ExecutionRegistryEntry(
                id, new ExecutionOwner.Foot(clientId),
                tenantId, projectId, "sess", null,
                "true", null,
                now, now, null,
                ExecutionStatus.RUNNING, null, null, null);
    }
}
