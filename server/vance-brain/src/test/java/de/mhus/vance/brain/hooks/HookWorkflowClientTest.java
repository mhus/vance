package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.hactar.HactarWorkflowService;
import de.mhus.vance.shared.hactar.HactarWorkflowParseException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HookWorkflowClientTest {

    private final HactarWorkflowService workflowService = mock(HactarWorkflowService.class);
    private final HookWorkflowClient client = new HookWorkflowClient(
            workflowService, "acme", "proj", "my-hook");

    @Test
    void happy_path_returns_runId() {
        when(workflowService.start(
                eq("acme"), eq("proj"), eq("flow"), any(), eq("hook:my-hook")))
                .thenReturn("ab12cd34");

        String runId = client.start("flow", Map.of("k", "v"));

        assertThat(runId).isEqualTo("ab12cd34");
    }

    @Test
    void overload_without_params_passes_empty_map() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenReturn("ab12cd34");

        client.start("flow");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.<Map<String, Object>>captor();
        verify(workflowService).start(any(), any(), eq("flow"), captor.capture(), any());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void startedBy_is_hook_prefix_plus_name() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenReturn("ab12cd34");

        client.start("flow", Map.of());

        verify(workflowService).start(any(), any(), any(), any(), eq("hook:my-hook"));
    }

    @Test
    void returns_null_when_workflow_service_is_absent() {
        HookWorkflowClient nullClient = new HookWorkflowClient(
                null, "acme", "proj", "hook-1");

        assertThat(nullClient.start("flow", Map.of())).isNull();
    }

    @Test
    void blank_name_returns_null_without_calling_service() {
        assertThat(client.start("", Map.of())).isNull();
        assertThat(client.start(" ", Map.of())).isNull();
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void workflow_not_found_returns_null() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new HactarWorkflowService.HactarWorkflowException(
                        "Workflow 'ghost' not found"));

        assertThat(client.start("ghost", Map.of())).isNull();
    }

    @Test
    void yaml_parse_failure_returns_null() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new HactarWorkflowParseException("bad shape"));

        assertThat(client.start("broken", Map.of())).isNull();
    }

    @Test
    void runtime_exception_returns_null() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThat(client.start("flow", Map.of())).isNull();
    }
}
