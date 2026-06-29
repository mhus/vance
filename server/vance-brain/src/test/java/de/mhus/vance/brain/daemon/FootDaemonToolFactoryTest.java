package de.mhus.vance.brain.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class FootDaemonToolFactoryTest {

    private DaemonRegistry registry;
    private WebSocketSender sender;
    private FootDaemonToolFactory factory;
    private WebSocketSession daemonWs;

    @BeforeEach
    void setUp() {
        registry = new DaemonRegistry();
        sender = mock(WebSocketSender.class);
        // Real invoker over the mocked sender — the WS interactions the
        // tests below capture/complete are unchanged by the extraction.
        factory = new FootDaemonToolFactory(registry, new DaemonToolInvoker(registry, sender));
        daemonWs = mock(WebSocketSession.class);
        // Phase B tests assume immediate-drop on disconnect; tests below
        // that exercise the stale path re-enable the TTL explicitly.
        registry.staleTtlSeconds = 0;
    }

    @Test
    void typeId_isFootDaemon() {
        assertThat(factory.typeId()).isEqualTo("foot_daemon");
    }

    @Test
    void create_returnsEmptyWhenDaemonOffline() {
        ServerToolDocument doc = doc("ops", "prod_box", Map.of("daemonName", "ghost"));

        Collection<Tool> tools = factory.create(doc, ctx());

        assertThat(tools).isEmpty();
    }

    @Test
    void create_surfacesAllManifestToolsAsSubTools() {
        registry.register(
                new DaemonRegistry.DaemonKey("acme", "ops", "server-prod-01"),
                daemonWs,
                List.of(
                        ToolSpec.builder().name("client_exec_run").description("Run shell").build(),
                        ToolSpec.builder().name("client_fs_read").description("Read file").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of("daemonName", "server-prod-01"));

        Collection<Tool> tools = factory.create(doc, ctx());

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder(
                "prod_box__client_exec_run", "prod_box__client_fs_read");
    }

    @Test
    void create_appliesExposedToolsWhitelist() {
        registry.register(
                new DaemonRegistry.DaemonKey("acme", "ops", "server-prod-01"),
                daemonWs,
                List.of(
                        ToolSpec.builder().name("client_exec_run").build(),
                        ToolSpec.builder().name("client_fs_read").build(),
                        ToolSpec.builder().name("client_fs_write").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of(
                "daemonName", "server-prod-01",
                "exposedTools", List.of("client_fs_read")));

        Collection<Tool> tools = factory.create(doc, ctx());

        assertThat(tools).extracting(Tool::name).containsExactly("prod_box__client_fs_read");
    }

    @Test
    void create_rejectsMissingDaemonName() {
        ServerToolDocument doc = doc("ops", "prod_box", Map.of());
        assertThatThrownBy(() -> factory.create(doc, ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daemonName");
    }

    @Test
    void invoke_routesViaWebSocketAndCompletesOnResult() throws Exception {
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of(
                "daemonName", "server-prod-01",
                "timeoutSeconds", 5));
        Tool subTool = factory.create(doc, ctx()).iterator().next();

        // Capture the invoke and complete the future from a separate
        // thread to simulate the daemon's CLIENT_TOOL_RESULT arriving.
        doAnswer(inv -> {
            ClientToolInvokeRequest req = inv.getArgument(2);
            new Thread(() -> {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                registry.completeInvocation(
                        req.getCorrelationId(),
                        Map.of("stdout", "hello", "exit", 0),
                        null);
            }).start();
            return null;
        }).when(sender).sendNotification(eq(daemonWs), eq(MessageType.CLIENT_TOOL_INVOKE), any());

        Map<String, Object> out = subTool.invoke(Map.of("command", "echo hello"), ctx());

        assertThat(out).containsEntry("stdout", "hello").containsEntry("exit", 0);
        verify(sender).sendNotification(eq(daemonWs), eq(MessageType.CLIENT_TOOL_INVOKE), any());
    }

    @Test
    void invoke_throwsWhenDaemonOfflineAtCallTime() {
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of("daemonName", "server-prod-01"));
        Tool subTool = factory.create(doc, ctx()).iterator().next();
        // Daemon disappears between materialise and invoke.
        registry.unregister(daemonWs);

        assertThatThrownBy(() -> subTool.invoke(Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("offline");
    }

    @Test
    void invoke_failsOnDaemonReportedError() throws Exception {
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of("daemonName", "server-prod-01"));
        Tool subTool = factory.create(doc, ctx()).iterator().next();

        doAnswer(inv -> {
            ClientToolInvokeRequest req = inv.getArgument(2);
            new Thread(() -> {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                registry.completeInvocation(req.getCorrelationId(), null, "permission denied");
            }).start();
            return null;
        }).when(sender).sendNotification(eq(daemonWs), eq(MessageType.CLIENT_TOOL_INVOKE), any());

        assertThatThrownBy(() -> subTool.invoke(Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void disconnect_failsInflightInvokes() throws Exception {
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        ServerToolDocument doc = doc("ops", "prod_box", Map.of(
                "daemonName", "server-prod-01", "timeoutSeconds", 5));
        Tool subTool = factory.create(doc, ctx()).iterator().next();

        doAnswer(inv -> {
            new Thread(() -> {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                registry.unregister(daemonWs);
            }).start();
            return null;
        }).when(sender).sendNotification(eq(daemonWs), eq(MessageType.CLIENT_TOOL_INVOKE), any());

        assertThatThrownBy(() -> subTool.invoke(Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("disconnected");
    }

    // ─── Phase C — stale daemon behaviour ───────────────────────────────

    @Test
    void create_surfacesToolsForStaleDaemon() {
        registry.staleTtlSeconds = 60;
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        registry.unregister(daemonWs);
        // Stale entry sits in the registry.
        assertThat(registry.find(key).orElseThrow().stale()).isTrue();

        Collection<Tool> tools = factory.create(
                doc("ops", "prod_box", Map.of("daemonName", "server-prod-01")), ctx());

        // Sub-tools stay visible so the chat-session listing doesn't
        // flicker during a reconnect.
        assertThat(tools).extracting(Tool::name).containsExactly("prod_box__client_exec_run");
    }

    @Test
    void invoke_onStaleDaemonReportsClearError() {
        registry.staleTtlSeconds = 60;
        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                "acme", "ops", "server-prod-01");
        registry.register(key, daemonWs,
                List.of(ToolSpec.builder().name("client_exec_run").build()));
        Tool subTool = factory.create(
                doc("ops", "prod_box", Map.of("daemonName", "server-prod-01")), ctx())
                .iterator().next();

        // Daemon disconnects between materialise and invoke → entry is
        // stale, not dropped (TTL > 0). The proxy must distinguish this
        // case from "never connected" and surface a helpful message.
        registry.unregister(daemonWs);

        assertThatThrownBy(() -> subTool.invoke(Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("offline")
                .hasMessageContaining("reconnect");
    }

    // ──────────────────── helpers ────────────────────

    private static ServerToolDocument doc(String projectId, String name, Map<String, Object> params) {
        ServerToolDocument d = new ServerToolDocument();
        d.setTenantId("acme");
        d.setProjectId(projectId);
        d.setName(name);
        d.setType("foot_daemon");
        d.setParameters(new HashMap<>(params));
        return d;
    }

    private static ToolInvocationContext ctx() {
        // The factory only reads tenantId / projectId from the document,
        // not from ctx — minimal record values are fine.
        return new ToolInvocationContext("acme", "ops", null, null, null);
    }
}
