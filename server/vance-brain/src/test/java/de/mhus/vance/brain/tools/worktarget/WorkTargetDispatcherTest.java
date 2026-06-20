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

import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkTargetDispatcherTest {

    private static final String PROC_ID = "proc-1";
    private static final String SESSION_ID = "session-1";

    private ThinkProcessService thinkProcessService;
    private ClientToolRegistry clientToolRegistry;
    private WorkTargetService workTargetService;
    private WorkTargetDispatcher dispatcher;
    private ToolBus bus;
    private ToolInvocationContext ctx;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        clientToolRegistry = mock(ClientToolRegistry.class);
        workTargetService = new WorkTargetService(thinkProcessService, clientToolRegistry);
        dispatcher = new WorkTargetDispatcher(workTargetService, thinkProcessService,
                mock(de.mhus.vance.brain.tools.ToolDispatcher.class));
        bus = mock(ToolBus.class);
        ctx = mock(ToolInvocationContext.class);
        lenient().when(ctx.processId()).thenReturn(PROC_ID);
        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setSessionId(SESSION_ID);
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        lenient().when(bus.invoke(any(), any())).thenReturn(Map.of("ok", true));
    }

    @Test
    void clientTarget_dispatchesToClientBackend_stripsDirName() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID))
                .thenReturn(Optional.of(mock(ClientToolRegistry.Entry.class)));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "Foo.java");
        params.put("dirName", "leftover");

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invoke(eq("client_file_read"), captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("path", "Foo.java");
        // CLIENT path strips dirName — Foot tools don't take it.
        assertThat(sent).doesNotContainKey("dirName");
    }

    @Test
    void workTarget_dispatchesToWorkBackend_injectsDirNameFromTarget() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "WORK", "dirName", "main"))));

        Map<String, Object> params = Map.of("path", "src/Foo.java");

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invoke(eq("work_file_read"), captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("path", "src/Foo.java")
                .containsEntry("dirName", "main");
    }

    @Test
    void workTarget_callerDirNameWins() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "WORK", "dirName", "main"))));

        Map<String, Object> params = Map.of("path", "Foo.java", "dirName", "build-output");

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invoke(eq("work_file_read"), captor.capture());
        // Caller's dirName overrides the active target's dirName.
        assertThat(captor.getValue()).containsEntry("dirName", "build-output");
    }

    @Test
    void workTarget_targetDirNameNull_doesNotInject() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "WORK"))));

        Map<String, Object> params = Map.of("path", "Foo.java");

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read", params);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bus).invoke(eq("work_file_read"), captor.capture());
        // No dirName injected → workspace tool's WorkspaceDirResolver
        // falls back to process-temp RootDir on its own.
        assertThat(captor.getValue()).doesNotContainKey("dirName");
    }

    @Test
    void clientTarget_footDisconnected_throws() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "CLIENT"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read",
                        Map.of("path", "Foo.java")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("CLIENT")
                .hasMessageContaining("no Foot client");
        verify(bus, never()).invoke(any(), any());
    }

    @Test
    void defaultResolution_noEngineParams_picksClientWhenConnected() {
        when(clientToolRegistry.entry(SESSION_ID))
                .thenReturn(Optional.of(mock(ClientToolRegistry.Entry.class)));

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read",
                Map.of("path", "Foo.java"));

        verify(bus).invoke(eq("client_file_read"), any());
    }

    @Test
    void defaultResolution_noEngineParams_picksWorkWhenNoFoot() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        dispatcher.dispatch(ctx, bus, "client_file_read", "work_file_read",
                Map.of("path", "Foo.java"));

        verify(bus).invoke(eq("work_file_read"), any());
    }
}
