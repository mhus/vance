package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.exec.SubmitOptions;
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
    private final de.mhus.vance.brain.tools.runner.RunnerWork runner =
            mock(de.mhus.vance.brain.tools.runner.RunnerWork.class);
    private final de.mhus.vance.shared.workspace.WorkspaceService workspace =
            mock(de.mhus.vance.shared.workspace.WorkspaceService.class);
    private final ShellTaskExecutor executor = new ShellTaskExecutor(execManager, objectMapper, runner, workspace);

    ShellTaskExecutorTest() {
        // No runner bound unless a test says so; a task without dirName gets a RootDir of its own.
        de.mhus.vance.exec.RootDirHandle temp = mock(de.mhus.vance.exec.RootDirHandle.class);
        when(temp.getDirName()).thenReturn("tmp-1");
        when(workspace.getOrCreateTempRootDir(any(), any(), any())).thenReturn(temp);
    }

    // ──────── the runner ────────

    private void runnerBound() {
        when(runner.available("acme", "proj")).thenReturn(true);
    }

    private static Map<String, Object> done(int exit) {
        return Map.of(
                "id",
                "job-1",
                "status",
                "COMPLETED",
                "exitCode",
                exit,
                "stdout",
                "ok\n",
                "stderr",
                "",
                "durationMs",
                3L);
    }

    @Test
    void with_a_runner_bound_the_command_runs_there_and_not_on_the_brain() {
        runnerBound();
        when(runner.call(any(), eq("exec.start"), any())).thenReturn(done(0));

        TaskOutcome outcome =
                executor.execute(ctx(scriptState("make", "build", null))).get();

        assertThat(outcome.outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        org.mockito.Mockito.verifyNoInteractions(execManager);
        // The deadline goes to the runner, which kills the job there.
        org.mockito.ArgumentCaptor<Map<String, Object>> args = org.mockito.ArgumentCaptor.captor();
        org.mockito.Mockito.verify(runner).call(any(), eq("exec.start"), args.capture());
        assertThat(args.getValue()).containsEntry("command", "make").containsEntry("dirName", "build");
        assertThat(args.getValue().get("deadlineSeconds")).isEqualTo(30L);
    }

    @Test
    void a_named_directory_is_used_as_it_is_and_never_created_or_deleted() {
        runnerBound();
        when(runner.call(any(), eq("exec.start"), any())).thenReturn(done(0));

        executor.execute(ctx(scriptState("make", "build", null)));

        org.mockito.Mockito.verify(runner, org.mockito.Mockito.never()).call(any(), eq("root.create"), any());
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.never()).call(any(), eq("root.delete"), any());
    }

    @Test
    void without_a_directory_the_task_gets_one_of_its_own_on_the_runner_and_it_is_removed_afterwards() {
        runnerBound();
        when(runner.call(any(), eq("root.create"), any())).thenReturn(Map.of("dirName", "tmp-r1"));
        when(runner.call(any(), eq("exec.start"), any())).thenReturn(done(0));

        executor.execute(ctx(scriptState("make", null, null)));

        org.mockito.ArgumentCaptor<Map<String, Object>> started = org.mockito.ArgumentCaptor.captor();
        org.mockito.Mockito.verify(runner).call(any(), eq("exec.start"), started.capture());
        assertThat(started.getValue()).containsEntry("dirName", "tmp-r1");
        org.mockito.Mockito.verify(runner).call(any(), eq("root.delete"), eq(Map.of("dirName", "tmp-r1")));
    }

    @Test
    void its_directory_is_removed_even_when_the_command_fails() {
        runnerBound();
        when(runner.call(any(), eq("root.create"), any())).thenReturn(Map.of("dirName", "tmp-r1"));
        when(runner.call(any(), eq("exec.start"), any())).thenReturn(done(2));

        TaskOutcome outcome =
                executor.execute(ctx(scriptState("false", null, null))).get();

        assertThat(outcome.outcome()).isEqualTo("business_error");
        org.mockito.Mockito.verify(runner).call(any(), eq("root.delete"), any());
    }

    @Test
    void a_command_still_running_after_the_first_wait_is_followed_until_it_has_ended() {
        runnerBound();
        when(runner.call(any(), eq("exec.start"), any()))
                .thenReturn(Map.of("id", "job-1", "status", "RUNNING", "stdout", "", "stderr", "", "durationMs", 1L));
        when(runner.call(any(), eq("exec.stat"), any())).thenReturn(Map.of("id", "job-1", "status", "COMPLETED"));
        when(runner.call(any(), eq("exec.status"), any())).thenReturn(done(0));

        TaskOutcome outcome =
                executor.execute(ctx(scriptState("sleep 2", "build", null))).get();

        assertThat(outcome.outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.output().get("stdout").asString()).contains("ok");
    }

    @Test
    void a_runner_that_refuses_is_a_technical_error_and_the_brain_is_not_used_instead() {
        runnerBound();
        when(runner.call(any(), eq("exec.start"), any()))
                .thenThrow(new de.mhus.vance.brain.tools.runner.RunnerRefusedException(
                        "exec-unsandboxed", "no exec wrapper"));

        TaskOutcome outcome =
                executor.execute(ctx(scriptState("make", "build", null))).get();

        assertThat(outcome.outcome()).isEqualTo("technical_error");
        assertThat(outcome.errorMessage()).contains("no exec wrapper");
        org.mockito.Mockito.verifyNoInteractions(execManager);
    }

    @Test
    void a_runner_that_does_not_answer_is_unavailable_and_the_brain_is_not_used_instead() {
        runnerBound();
        when(runner.call(any(), eq("exec.start"), any()))
                .thenThrow(new de.mhus.vance.toolpack.ToolException("connection refused"));

        TaskOutcome outcome =
                executor.execute(ctx(scriptState("make", "build", null))).get();

        assertThat(outcome.outcome()).isEqualTo("unavailable");
        org.mockito.Mockito.verifyNoInteractions(execManager);
    }

    @Test
    void cancelling_a_command_on_the_runner_kills_its_job_there() throws Exception {
        runnerBound();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        when(runner.call(any(), eq("exec.start"), any())).thenAnswer(inv -> {
            started.countDown();
            return Map.of("id", "job-1", "status", "RUNNING", "stdout", "", "stderr", "", "durationMs", 1L);
        });
        when(runner.call(any(), eq("exec.stat"), any())).thenReturn(Map.of("id", "job-1", "status", "KILLED"));
        when(runner.call(any(), eq("exec.status"), any()))
                .thenReturn(Map.of("id", "job-1", "status", "KILLED", "stdout", "", "stderr", "", "durationMs", 2L));
        when(runner.call(any(), eq("exec.kill"), any())).thenReturn(Map.of("id", "job-1", "killed", true));
        Thread waiting = Thread.ofVirtual().start(() -> executor.execute(ctx(scriptState("sleep 600", "build", null))));
        assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // the job is registered as soon as exec.start has answered

        executor.cancelRunning(ctx(scriptState("sleep 600", "build", null)));

        // Interrupting the thread would not have ended the program on the runner; the kill does.
        org.mockito.Mockito.verify(runner).call(any(), eq("exec.kill"), eq(Map.of("id", "job-1")));
        waiting.join(5000);
    }

    @Test
    void without_a_runner_a_task_without_a_directory_gets_one_of_its_own_on_the_brain_and_it_is_removed() {
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
                .thenReturn(done(0));

        executor.execute(ctx(scriptState("ls", null, null)));

        org.mockito.Mockito.verify(workspace).getOrCreateTempRootDir(eq("acme"), eq("proj"), any());
        org.mockito.Mockito.verify(workspace).disposeByCreator(eq("acme"), eq("proj"), any());
    }

    @Test
    void a_shell_command_runs_off_the_lane() {
        assertThat(executor.runsOffLane()).isTrue();
    }

    @Test
    void cancelling_a_running_command_kills_its_job_not_just_the_waiting_thread() {
        // The job id is announced right after submit, before the wait; the wait is what is still going.
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> onJobId = inv.getArgument(8);
                    onJobId.accept("job-77");
                    // Still running while the test cancels: nothing to return yet.
                    executor.cancelRunning(ctx(scriptState("sleep 600", null, null)));
                    return Map.of("id", "job-77", "status", "KILLED", "stdout", "", "stderr", "", "durationMs", 5L);
                });

        executor.execute(ctx(scriptState("sleep 600", null, null)));

        org.mockito.Mockito.verify(execManager).kill("acme", "proj", "job-77");
    }

    @Test
    void cancelling_a_task_with_no_running_job_kills_nothing() {
        executor.cancelRunning(ctx(scriptState("ls", null, null)));

        org.mockito.Mockito.verify(execManager, org.mockito.Mockito.never()).kill(any(), any(), any());
    }

    @Test
    void a_finished_command_leaves_nothing_to_cancel() {
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> onJobId = inv.getArgument(8);
                    onJobId.accept("job-1");
                    return Map.of(
                            "id",
                            "job-1",
                            "status",
                            "COMPLETED",
                            "exitCode",
                            0,
                            "stdout",
                            "",
                            "stderr",
                            "",
                            "durationMs",
                            1L);
                });
        executor.execute(ctx(scriptState("true", null, null)));

        executor.cancelRunning(ctx(scriptState("true", null, null)));

        org.mockito.Mockito.verify(execManager, org.mockito.Mockito.never()).kill(any(), any(), any());
    }

    @Test
    void completed_exit_zero_yields_success() {
        when(execManager.submitTrackedAndRender(
                        eq("acme"),
                        eq("proj"),
                        any(),
                        any(),
                        any(),
                        eq("ls -la"),
                        eq(30_000L),
                        any(SubmitOptions.class),
                        any()))
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
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
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
    void a_failed_job_that_carries_an_exit_code_is_the_commands_no_not_an_infrastructure_error() {
        // ExecJobs ends a process with a non-zero exit code as FAILED, with the code — only a job that
        // could not run at all is FAILED without one.
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
                .thenReturn(Map.of(
                        "id", "abc",
                        "status", "FAILED",
                        "exitCode", 2,
                        "stdout", "",
                        "stderr", "no such file",
                        "durationMs", 5L));

        Optional<TaskOutcome> outcome = executor.execute(ctx(scriptState("cat missing", null, null)));

        assertThat(outcome.get().outcome()).isEqualTo("business_error");
        assertThat(outcome.get().errorMessage()).contains("exit 2").contains("no such file");
    }

    @Test
    void killed_status_yields_timeout() {
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
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
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
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
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
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
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(SubmitOptions.class), any()))
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
        when(execManager.submitTrackedAndRender(
                        any(), any(), any(), any(), any(), any(), eq(60_000L), any(SubmitOptions.class), any()))
                .thenReturn(Map.of(
                        "id", "x", "status", "COMPLETED", "exitCode", 0, "stdout", "", "stderr", "", "durationMs", 1L));

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
                null,
                java.util.List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme",
                "proj",
                "r1",
                "task-1",
                "alice",
                new ResolvedMagratheaWorkflow(
                        "noop",
                        "",
                        MagratheaWorkflowSource.PROJECT,
                        null,
                        null,
                        null,
                        null,
                        "start",
                        Map.of(),
                        Map.of(),
                        MagratheaBoundsSpec.empty(),
                        List.of(),
                        List.of()),
                state,
                Map.of(),
                Map.of(),
                null,
                null);
    }
}
