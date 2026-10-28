package de.mhus.vance.brain.tools.worktarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.PodCapabilities;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.runner.RunnerBinding;
import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkTargetServiceTest {

    private static final String PROC_ID = "proc-1";
    private static final String SESSION_ID = "session-1";

    private ThinkProcessService thinkProcessService;
    private ClientToolRegistry clientToolRegistry;
    private final RunnerBindingService runnerBindings = mock(RunnerBindingService.class);
    private WorkTargetService service;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        clientToolRegistry = mock(ClientToolRegistry.class);
        service = new WorkTargetService(
                thinkProcessService,
                clientToolRegistry,
                new PodCapabilities(new WorkspaceProperties(), List.of()),
                runnerBindings);
        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setSessionId(SESSION_ID);
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    @Test
    void current_noEngineParams_returnsClientWhenFootConnected() {
        when(clientToolRegistry.entry(SESSION_ID))
                .thenReturn(Optional.of(new ClientToolRegistry.Entry(
                        "editor-foot",
                        null,
                        Map.of(
                                "client_exec_run",
                                de.mhus.vance.api.tools.ToolSpec.builder()
                                        .name("client_exec_run")
                                        .build()))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.CLIENT);
    }

    @Test
    void current_noEngineParams_returnsWorkWhenNoFoot() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
        assertThat(t.targetName()).isNull();
    }

    @Test
    void current_noEngineParams_webClientRegistrationStaysWork() {
        // A web client registers browser tools (location_get) in the same
        // registry — that is a registered client but not a Foot: no exec/file
        // backends, so the default must stay WORK. The old any-entry check
        // flipped web sessions onto a dead CLIENT target.
        when(clientToolRegistry.entry(SESSION_ID))
                .thenReturn(Optional.of(new ClientToolRegistry.Entry(
                        "editor-web",
                        null,
                        Map.of(
                                "location_get",
                                de.mhus.vance.api.tools.ToolSpec.builder()
                                        .name("location_get")
                                        .build()))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
    }

    @Test
    void current_engineParamsClient_returnsClient() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "CLIENT"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.CLIENT);
        assertThat(t.targetName()).isNull();
    }

    @Test
    void current_engineParamsWorkWithTargetName_returnsWork() {
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "targetName", "tmp52"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
        assertThat(t.targetName()).isEqualTo("tmp52");
    }

    @Test
    void current_engineParamsLegacyDirName_stillResolves() {
        // Pre-rename engineParams used the "dirName" sub-key — fromMap
        // tolerates it so existing processes keep working.
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "WORK", "dirName", "legacy-root"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
        assertThat(t.targetName()).isEqualTo("legacy-root");
    }

    @Test
    void current_engineParamsDaemon_returnsDaemon() {
        process.setEngineParams(
                new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "DAEMON", "targetName", "build-box"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.DAEMON);
        assertThat(t.targetName()).isEqualTo("build-box");
    }

    @Test
    void current_engineParamsMalformed_fallsBackToDefault() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(WorkTarget.KEY, Map.of("kind", "garbage"))));
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        WorkTarget t = service.current(process);

        // Falls back to default — no exception thrown.
        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
    }

    @Test
    void set_writesAndPreservesOtherEngineParams() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                "otherKey", "otherValue",
                "model", "default:fast")));
        when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any())).thenReturn(true);

        service.set(PROC_ID, WorkTarget.work("main"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), captor.capture());
        Map<String, Object> persisted = captor.getValue();
        assertThat(persisted).containsKeys("otherKey", "model", WorkTarget.KEY);
        assertThat(persisted.get("otherKey")).isEqualTo("otherValue");
        @SuppressWarnings("unchecked")
        Map<String, Object> wt = (Map<String, Object>) persisted.get(WorkTarget.KEY);
        assertThat(wt).containsEntry("kind", "WORK").containsEntry("targetName", "main");
    }

    @Test
    void set_clientTarget_dropsTargetName() {
        when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any())).thenReturn(true);

        service.set(PROC_ID, WorkTarget.client());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> wt = (Map<String, Object>) captor.getValue().get(WorkTarget.KEY);
        assertThat(wt).containsEntry("kind", "CLIENT");
        assertThat(wt).doesNotContainKey("targetName");
    }

    @Test
    void set_daemonTarget_persistsName() {
        when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any())).thenReturn(true);

        service.set(PROC_ID, WorkTarget.daemon("build-box"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> wt = (Map<String, Object>) captor.getValue().get(WorkTarget.KEY);
        assertThat(wt).containsEntry("kind", "DAEMON").containsEntry("targetName", "build-box");
    }

    @Test
    void set_workTargetWithWorkspaceOff_isRefused() {
        WorkspaceProperties off = new WorkspaceProperties();
        off.setMode(WorkspaceMode.OFF);
        WorkTargetService offService = new WorkTargetService(
                thinkProcessService, clientToolRegistry, new PodCapabilities(off, List.of()), runnerBindings);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> offService.set(PROC_ID, WorkTarget.work(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("vance.workspace.mode=off");
    }

    @Test
    void defaultFor_workspaceOffWithoutClient_isNone() {
        WorkspaceProperties off = new WorkspaceProperties();
        off.setMode(WorkspaceMode.OFF);
        WorkTargetService offService = new WorkTargetService(
                thinkProcessService, clientToolRegistry, new PodCapabilities(off, List.of()), runnerBindings);

        assertThat(offService.defaultFor(process).kind()).isEqualTo(WorkTargetKind.NONE);
    }

    @Test
    void defaultFor_workspaceLocalWithoutClient_staysWork() {
        assertThat(service.defaultFor(process).kind()).isEqualTo(WorkTargetKind.WORK);
    }

    @Test
    void set_noneTarget_isAccepted() {
        when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any())).thenReturn(true);

        service.set(PROC_ID, WorkTarget.none());

        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), any());
    }

    private void registerRunner() {
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId("runner-acme")
                .url("http://runner:9840")
                .recipient("age1xyz")
                .build();
        when(runnerBindings.resolve(any(), any()))
                .thenReturn(
                        Optional.of(new RunnerBindingService.Effective(binding, RunnerBindingService.Source.TENANT)));
    }

    private WorkTargetService workspaceOffService() {
        WorkspaceProperties off = new WorkspaceProperties();
        off.setMode(WorkspaceMode.OFF);
        return new WorkTargetService(
                thinkProcessService, clientToolRegistry, new PodCapabilities(off, List.of()), runnerBindings);
    }

    @Test
    void defaultFor_workspaceOffWithARunner_isTheRunner() {
        registerRunner();

        assertThat(workspaceOffService().defaultFor(process).kind()).isEqualTo(WorkTargetKind.RUNNER);
    }

    @Test
    void defaultFor_workspaceLocalWithARunner_staysWork() {
        registerRunner();

        // Both surfaces exist; the agent picks the runner explicitly.
        assertThat(service.defaultFor(process).kind()).isEqualTo(WorkTargetKind.WORK);
    }

    @Test
    void set_runnerWithoutARegisteredRunner_isRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.set(PROC_ID, WorkTarget.runner(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No runner is registered");
    }

    @Test
    void set_runnerWithARunner_isStored_evenWithoutAWorkspace() {
        registerRunner();
        when(thinkProcessService.replaceEngineParams(any(), any())).thenReturn(true);

        workspaceOffService().set(PROC_ID, WorkTarget.runner("build"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue().get(WorkTarget.KEY)).isEqualTo(Map.of("kind", "RUNNER", "targetName", "build"));
    }
}
