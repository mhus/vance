package de.mhus.vance.brain.tools.worktarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.daemon.DaemonRegistry;
import de.mhus.vance.brain.daemon.DaemonToolInvoker;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkTargetDispatcherTest {

    private static final String PROC_ID = "proc-1";
    private static final String SESSION_ID = "session-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String PROJECT_ID = "project-1";

    private ThinkProcessService thinkProcessService;
    private ClientToolRegistry clientToolRegistry;
    private WorkTargetService workTargetService;
    private DaemonToolInvoker daemonToolInvoker;
    private de.mhus.vance.brain.tools.ToolDispatcher toolDispatcher;
    private WorkTargetDispatcher dispatcher;
    private ToolBus bus;
    private ToolInvocationContext ctx;
    private ThinkProcessDocument process;
    private Tool wrapper;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        clientToolRegistry = mock(ClientToolRegistry.class);
        daemonToolInvoker = mock(DaemonToolInvoker.class);
        toolDispatcher = mock(de.mhus.vance.brain.tools.ToolDispatcher.class);
        workTargetService = new WorkTargetService(thinkProcessService, clientToolRegistry);
        dispatcher =
                new WorkTargetDispatcher(workTargetService, thinkProcessService, toolDispatcher, daemonToolInvoker);
        wrapper = stubTool("file_read", "path", "dirName", "maxChars");
        bus = mock(ToolBus.class);
        ctx = mock(ToolInvocationContext.class);
        lenient().when(ctx.processId()).thenReturn(PROC_ID);
        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setSessionId(SESSION_ID);
        process.setTenantId(TENANT_ID);
        process.setProjectId(PROJECT_ID);
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        lenient().when(bus.invokeDelegate(any(), any())).thenReturn(Map.of("ok", true));
    }

    @Test
    void dispatch_usesInvokeDelegate_soDeferredBackendsAreNotActivated() {
        // Backends are deferred. A plain bus.invoke() would count as the LLM
        // discovering the backend and promote it into the next turn's
        // manifest — which would put file_read and work_file_read side by
        // side again, the exact ambiguity the wrapper removes.
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK"))));

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "a.txt"));

        verify(bus).invokeDelegate(eq("work_file_read"), any());
        verify(bus, never()).invoke(any(), any());
    }

    @Test
    void clientTarget_dispatchesToClientBackend_stripsDirName() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.of(footEntry()));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "Foo.java");
        params.put("dirName", "leftover");

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invokeDelegate(eq("client_file_read"), captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("path", "Foo.java");
        // CLIENT path strips dirName — Foot tools don't take it.
        assertThat(sent).doesNotContainKey("dirName");
    }

    @Test
    void clientTarget_backendOutsidePool_saysHowToSwitchToWork() {
        // The web-profile failure mode: a client is bound to the session
        // (so the connected-check passes), but the engine's toolset carries
        // no client_* backends. The raw pool error would read as "exec is
        // unavailable" — the enriched message names the recovery.
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.of(footEntry()));
        when(bus.invokeDelegate(eq("client_exec_run"), any()))
                .thenThrow(new ToolException("Tool 'client_exec_run' is not in this engine's dispatch pool"));

        assertThatThrownBy(() -> dispatcher.dispatch(
                        ctx,
                        bus,
                        stubTool("exec_run", "command", "dirName", "waitMs"),
                        "client_exec_run",
                        "work_exec_run",
                        Map.of("command", "curl -s x")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("WorkTarget is CLIENT")
                .hasMessageContaining("work_target_set(kind=\"WORK\")")
                .hasMessageContaining("work_exec_run");
    }

    @Test
    void workTarget_dispatchesToWorkBackend_injectsDirNameFromTarget() {
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "targetName", "main"))));

        Map<String, Object> params = Map.of("path", "src/Foo.java");

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invokeDelegate(eq("work_file_read"), captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("path", "src/Foo.java").containsEntry("dirName", "main");
    }

    @Test
    void workTarget_callerDirNameWins() {
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "targetName", "main"))));

        Map<String, Object> params = Map.of("path", "Foo.java", "dirName", "build-output");

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invokeDelegate(eq("work_file_read"), captor.capture());
        // Caller's dirName overrides the active target's dirName.
        assertThat(captor.getValue()).containsEntry("dirName", "build-output");
    }

    @Test
    void workTarget_targetDirNameNull_doesNotInject() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK"))));

        Map<String, Object> params = Map.of("path", "Foo.java");

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invokeDelegate(eq("work_file_read"), captor.capture());
        // No dirName injected → workspace tool's WorkspaceDirResolver
        // falls back to process-temp RootDir on its own.
        assertThat(captor.getValue()).doesNotContainKey("dirName");
    }

    @Test
    void clientTarget_footDisconnected_throws() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatcher.dispatch(
                        ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "Foo.java")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("CLIENT")
                .hasMessageContaining("no Foot client");
        verify(bus, never()).invokeDelegate(any(), any());
    }

    @Test
    void defaultResolution_noEngineParams_picksClientWhenConnected() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.of(footEntry()));

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "Foo.java"));

        verify(bus).invokeDelegate(eq("client_file_read"), any());
    }

    @Test
    void defaultResolution_webClientRegistration_picksWork() {
        // The regression this pins: web clients register browser tools
        // (location_get) in the same registry. Any-entry-presence used to
        // flip the default onto a dead CLIENT target; only a registration
        // carrying the exec/file backends counts as a Foot.
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.of(webEntry()));

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "Foo.java"));

        verify(bus).invokeDelegate(eq("work_file_read"), any());
    }

    @Test
    void defaultResolution_noEngineParams_picksWorkWhenNoFoot() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "Foo.java"));

        verify(bus).invokeDelegate(eq("work_file_read"), any());
    }

    @Test
    void daemonTarget_routesClientBackendOverDaemon_stripsDirName() {
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "DAEMON", "targetName", "build-box"))));
        when(daemonToolInvoker.invoke(any(), eq("client_file_read"), any(), any()))
                .thenReturn(Map.of("ok", true));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "Foo.java");
        params.put("dirName", "leftover");

        Map<String, Object> result =
                dispatcher.dispatch(ctx, bus, wrapper, "client_file_read", "work_file_read", params);

        assertThat(result).containsEntry("ok", true);
        // DAEMON routes the client_* tool over the daemon's WS — never the bus.
        verify(bus, never()).invokeDelegate(any(), any());

        ArgumentCaptor<DaemonRegistry.DaemonKey> keyCaptor = ArgumentCaptor.forClass(DaemonRegistry.DaemonKey.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(daemonToolInvoker)
                .invoke(
                        keyCaptor.capture(), eq("client_file_read"),
                        paramsCaptor.capture(), any(Duration.class));
        DaemonRegistry.DaemonKey key = keyCaptor.getValue();
        assertThat(key.tenantId()).isEqualTo(TENANT_ID);
        assertThat(key.projectId()).isEqualTo(PROJECT_ID);
        assertThat(key.daemonName()).isEqualTo("build-box");
        // Foot client tools don't take dirName.
        assertThat(paramsCaptor.getValue()).containsEntry("path", "Foo.java").doesNotContainKey("dirName");
    }

    // ─── Param validation: report instead of silently dropping ──────────

    @Test
    void unknownParam_isRejectedWithTheAcceptedNames() {
        // The wrapper, its CLIENT backend and its WORK backend are three
        // separate schemas that drift. A param none of them knows used to
        // travel along and vanish, leaving the caller unable to tell
        // "ignored" from "no effect" — and re-trying with variations.
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "targetName", "main"))));
        backendIs("work_file_read", stubTool("work_file_read", "path", "dirName", "maxChars"));

        assertThatThrownBy(() -> dispatcher.dispatch(
                        ctx,
                        bus,
                        wrapper,
                        "client_file_read",
                        "work_file_read",
                        Map.of("path", "Foo.java", "offset", 200)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("file_read")
                .hasMessageContaining("offset")
                .hasMessageContaining("maxChars");
        verify(bus, never()).invokeDelegate(any(), any());
    }

    @Test
    void backendOnlyParam_isAccepted_wrapperSchemaIsNotTheWholeContract() {
        // Backends legitimately expose more than the wrapper advertises
        // (caseInsensitive on grep, startLine on the client reader). Those
        // calls have always worked; validation must not take them away.
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.of(footEntry()));
        backendIs("client_file_read", stubTool("client_file_read", "path", "startLine", "maxLines"));

        dispatcher.dispatch(
                ctx, bus, wrapper, "client_file_read", "work_file_read", Map.of("path", "Foo.java", "startLine", 300));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invokeDelegate(eq("client_file_read"), captor.capture());
        assertThat(captor.getValue()).containsEntry("startLine", 300);
    }

    @Test
    void unresolvableBackend_failsOpen_ratherThanBlockingTheCall() {
        // Validation exists to help; it must never be the reason a working
        // call starts failing. No backend schema → no rejection.
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "targetName", "main"))));
        when(toolDispatcher.resolve(any(), any())).thenReturn(Optional.empty());

        dispatcher.dispatch(
                ctx,
                bus,
                wrapper,
                "client_file_read",
                "work_file_read",
                Map.of("path", "Foo.java", "totallyMadeUp", 1));

        verify(bus).invokeDelegate(eq("work_file_read"), any());
    }

    private void backendIs(String name, Tool tool) {
        when(toolDispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(new de.mhus.vance.brain.tools.ToolDispatcher.Resolved(tool, null)));
    }

    /**
     * A session registration that carries the foot exec/file backends —
     * what a connected Foot CLI announces, and the only thing that
     * counts as a CLIENT work target.
     */
    private static ClientToolRegistry.Entry footEntry() {
        return new ClientToolRegistry.Entry(
                "editor-foot",
                null,
                Map.of(
                        "client_file_read", toolSpec("client_file_read"),
                        "client_exec_run", toolSpec("client_exec_run")));
    }

    /** A web client's registration: browser tools only, no exec/file backends. */
    private static ClientToolRegistry.Entry webEntry() {
        return new ClientToolRegistry.Entry("editor-web", null, Map.of("location_get", toolSpec("location_get")));
    }

    private static ToolSpec toolSpec(String name) {
        return ToolSpec.builder().name(name).build();
    }

    /** Minimal {@link Tool} that only carries a name and a param schema. */
    private static Tool stubTool(String name, String... params) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String p : params) {
            props.put(p, Map.of("type", "string"));
        }
        Map<String, Object> schema = Map.of("type", "object", "properties", props);
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public boolean primary() {
                return true;
            }

            @Override
            public Map<String, Object> paramsSchema() {
                return schema;
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
    }
}
