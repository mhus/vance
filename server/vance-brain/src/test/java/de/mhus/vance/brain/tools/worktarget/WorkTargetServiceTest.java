package de.mhus.vance.brain.tools.worktarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import java.util.LinkedHashMap;
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
    private WorkTargetService service;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        clientToolRegistry = mock(ClientToolRegistry.class);
        service = new WorkTargetService(thinkProcessService, clientToolRegistry);
        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setSessionId(SESSION_ID);
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    @Test
    void current_noEngineParams_returnsClientWhenFootConnected() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(
                Optional.of(mock(ClientToolRegistry.Entry.class)));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.CLIENT);
    }

    @Test
    void current_noEngineParams_returnsWorkWhenNoFoot() {
        when(clientToolRegistry.entry(SESSION_ID)).thenReturn(Optional.empty());

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
        assertThat(t.dirName()).isNull();
    }

    @Test
    void current_engineParamsClient_returnsClient() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "CLIENT"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.CLIENT);
        assertThat(t.dirName()).isNull();
    }

    @Test
    void current_engineParamsWorkWithDirName_returnsWork() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "WORK", "dirName", "tmp52"))));

        WorkTarget t = service.current(process);

        assertThat(t.kind()).isEqualTo(WorkTargetKind.WORK);
        assertThat(t.dirName()).isEqualTo("tmp52");
    }

    @Test
    void current_engineParamsMalformed_fallsBackToDefault() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(
                WorkTarget.KEY, Map.of("kind", "garbage"))));
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
        assertThat(wt).containsEntry("kind", "WORK").containsEntry("dirName", "main");
    }

    @Test
    void set_clientTarget_dropsDirName() {
        when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any())).thenReturn(true);

        service.set(PROC_ID, WorkTarget.client());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> wt = (Map<String, Object>) captor.getValue().get(WorkTarget.KEY);
        assertThat(wt).containsEntry("kind", "CLIENT");
        assertThat(wt).doesNotContainKey("dirName");
    }
}
