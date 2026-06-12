package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.SubmitOptions;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outcome-mapping tests for {@link ShellTaskExecutor}. {@link ExecManager}
 * is mocked — we drive the executor with the same response shapes
 * {@code submitTrackedAndRender} produces in production.
 */
class ShellTaskExecutorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ExecManager execManager = mock(ExecManager.class);
    private final ShellTaskExecutor executor = new ShellTaskExecutor(execManager, objectMapper);

    @Test
    void completed_exit_zero_yields_success() {
        when(execManager.submitTrackedAndRender(
                eq("acme"), eq("proj"), any(), any(), any(), eq("ls -la"), eq(30_000L),
                any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "COMPLETED",
                        "exitCode", 0,
                        "stdout", "total 0\n",
                        "stderr", "",
                        "durationMs", 12L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("ls -la", null, null)));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output().get("exitCode").asInt()).isEqualTo(0);
        assertThat(outcome.get().output().get("stdout").asString()).contains("total 0");
    }

    @Test
    void completed_nonzero_exit_yields_business_error() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "COMPLETED",
                        "exitCode", 1,
                        "stdout", "",
                        "stderr", "permission denied",
                        "durationMs", 7L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("false", null, null)));

        assertThat(outcome.get().outcome()).isEqualTo("business_error");
        assertThat(outcome.get().errorMessage()).contains("exit 1").contains("permission denied");
    }

    @Test
    void killed_status_yields_timeout() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "KILLED",
                        "stdout", "",
                        "stderr", "",
                        "durationMs", 1800_000L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("sleep 9999", null, null)));

        assertThat(outcome.get().outcome()).isEqualTo("timeout");
        assertThat(outcome.get().errorMessage()).contains("watchdog");
    }

    @Test
    void running_at_wait_exhaustion_yields_timeout() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "RUNNING",
                        "stdout", "",
                        "stderr", "",
                        "durationMs", 30_000L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("sleep 9999", null, 30)));

        assertThat(outcome.get().outcome()).isEqualTo("timeout");
        assertThat(outcome.get().errorMessage()).contains("wait exhausted");
    }

    @Test
    void failed_status_yields_technical_error() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "FAILED",
                        "stdout", "",
                        "stderr", "",
                        "durationMs", 1L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("bogus", null, null)));

        assertThat(outcome.get().outcome()).isEqualTo("technical_error");
    }

    @Test
    void exec_manager_RuntimeException_yields_technical_error() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class)))
                .thenThrow(new RuntimeException("Unknown workspace RootDir"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("ls", "missing", null)));

        assertThat(outcome.get().outcome()).isEqualTo("technical_error");
        assertThat(outcome.get().errorMessage()).contains("Unknown workspace RootDir");
    }

    @Test
    void missing_run_field_fails_at_executor() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState(null, null, null)));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'run:'");
    }

    @Test
    void timeoutSeconds_converted_to_waitMs() {
        when(execManager.submitTrackedAndRender(any(), any(), any(), any(), any(), any(), eq(60_000L),
                any(SubmitOptions.class)))
                .thenReturn(Map.of(
                        "id", "x", "status", "COMPLETED", "exitCode", 0,
                        "stdout", "", "stderr", "", "durationMs", 1L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("true", null, 60)));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    // ──────── helpers ────────

    private static MagratheaStateSpec scriptState(String run, String dirName, Integer timeoutSeconds) {
        java.util.Map<String, Object> spec = new java.util.LinkedHashMap<>();
        if (run != null) spec.put("run", run);
        if (dirName != null) spec.put("dirName", dirName);
        return new MagratheaStateSpec(
                "run_checks",
                MagratheaTaskType.SHELL_TASK,
                null,
                timeoutSeconds,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
