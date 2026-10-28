package de.mhus.vance.brain.tools.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionOwner;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** What the brain does about a job a runner runs: registry entry, polling, the completion event. */
class RunnerJobsTest {

    private final RunnerWork runner = mock(RunnerWork.class);
    private final ExecutionRegistryService registry = new ExecutionRegistryService();
    private final EngineMessageRouter router = mock(EngineMessageRouter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EngineMessageRouter> routerProvider = mock(ObjectProvider.class);

    private RunnerJobs jobs;

    @BeforeEach
    void setUp() {
        when(routerProvider.getObject()).thenReturn(router);
        when(router.dispatch(any(), any(), any())).thenReturn(true);
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.of("runner-acme"));
        jobs = new RunnerJobs(
                runner,
                registry,
                routerProvider,
                new ExecProperties(),
                Duration.ofMillis(10),
                Duration.ofMillis(20),
                3);
    }

    @AfterEach
    void tearDown() {
        jobs.shutdown();
    }

    private static ToolInvocationContext ctx(String processId) {
        return new ToolInvocationContext("acme", "p1", "s1", processId, "alice");
    }

    private void statAnswers(Map<String, Object>... answers) {
        AtomicInteger n = new AtomicInteger();
        when(runner.call(any(), eq("exec.stat"), any()))
                .thenAnswer(inv -> answers[Math.min(n.getAndIncrement(), answers.length - 1)]);
    }

    private void tails(List<String> stdout, List<String> stderr) {
        when(runner.call(any(), eq("exec.tail"), any())).thenAnswer(inv -> {
            Map<?, ?> args = inv.getArgument(2);
            return Map.of("lines", "stderr".equals(args.get("stream")) ? stderr : stdout);
        });
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRunningJobIsRegistered_polledToItsEnd_andItsOwnerGetsExecFinished() {
        statAnswers(
                Map.of("status", "RUNNING"),
                Map.of("status", "COMPLETED", "exitCode", 0, "finishedAt", "2026-10-28T20:00:00Z"));
        tails(List.of("built"), List.of());

        jobs.watch(ctx("proc-1"), Map.of("id", "j1", "status", "RUNNING"), "make", "src");

        ExecutionRegistryEntry started = registry.find("j1").orElseThrow();
        assertThat(started.owner()).isEqualTo(new ExecutionOwner.Runner("runner-acme"));
        assertThat(started.processId()).isEqualTo("proc-1");
        assertThat(started.command()).isEqualTo("make");
        assertThat(started.dirName()).isEqualTo("src");

        ArgumentCaptor<PendingMessageDocument> message = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router, org.mockito.Mockito.timeout(5_000)).dispatch(eq("proc-1"), eq("proc-1"), message.capture());
        assertThat(message.getValue().getEventType()).isEqualTo(ProcessEventType.EXEC_FINISHED);
        assertThat(message.getValue().getContent())
                .contains("j1")
                .contains("completed")
                .contains("exit 0");
        assertThat(message.getValue().getPayload()).containsEntry("jobId", "j1").containsEntry("exitCode", 0);
        assertThat((List<String>) message.getValue().getPayload().get("stdoutTail"))
                .containsExactly("built");
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() ->
                        assertThat(registry.find("j1").orElseThrow().status()).isEqualTo(ExecutionStatus.COMPLETED));
        assertThat(registry.find("j1").orElseThrow().exitCode()).isZero();
    }

    @Test
    void aJobThatHitItsDeadlineIsAnExecTimeout_notAFinishedRun() {
        statAnswers(Map.of("status", "KILLED", "timedOut", true, "finishedAt", "2026-10-28T20:00:00Z"));
        tails(List.of("partial"), List.of());

        jobs.watch(ctx("proc-1"), Map.of("id", "j2", "status", "RUNNING"), "sleep 999", "src");

        ArgumentCaptor<PendingMessageDocument> message = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router, org.mockito.Mockito.timeout(5_000)).dispatch(eq("proc-1"), eq("proc-1"), message.capture());
        assertThat(message.getValue().getEventType()).isEqualTo(ProcessEventType.EXEC_TIMEOUT);
        assertThat(message.getValue().getContent()).contains("did NOT finish");
    }

    @Test
    void aRunnerThatStaysUnreachable_ordersTheJobOrphaned_andTellsTheOwner() {
        when(runner.call(any(), eq("exec.stat"), any()))
                .thenThrow(new ToolException("Runner 'runner-acme' is not reachable"));
        when(runner.call(any(), eq("exec.tail"), any())).thenThrow(new ToolException("not reachable"));

        jobs.watch(ctx("proc-1"), Map.of("id", "j3", "status", "RUNNING"), "sleep 60", "src");

        ArgumentCaptor<PendingMessageDocument> message = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router, org.mockito.Mockito.timeout(5_000)).dispatch(eq("proc-1"), eq("proc-1"), message.capture());
        assertThat(message.getValue().getContent()).contains("orphaned");
        assertThat(registry.find("j3").orElseThrow().status()).isEqualTo(ExecutionStatus.ORPHANED);
    }

    @Test
    void aKillTheUserAsksFor_endsTheWatchWithoutAnEvent() throws Exception {
        when(runner.call(any(), eq("exec.stat"), any())).thenReturn(Map.of("status", "RUNNING"));
        when(runner.call(any(), eq("exec.kill"), any())).thenReturn(Map.of("id", "j4", "killed", true));

        jobs.watch(ctx("proc-1"), Map.of("id", "j4", "status", "RUNNING"), "sleep 60", "src");
        Map<String, Object> killed = jobs.kill("acme", "p1", "j4");
        Thread.sleep(100);

        assertThat(killed.get("killed")).isEqualTo(true);
        assertThat(registry.find("j4").orElseThrow().status()).isEqualTo(ExecutionStatus.KILLED);
        verify(router, never()).dispatch(any(), any(), any());
    }

    @Test
    void aJobWithoutAnOwnerProcessIsTrackedButNobodyIsTold() {
        statAnswers(Map.of("status", "FAILED", "exitCode", 2));
        tails(List.of(), List.of("boom"));

        jobs.watch(ctx(null), Map.of("id", "j5", "status", "RUNNING"), "false", "src");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() ->
                        assertThat(registry.find("j5").orElseThrow().status()).isEqualTo(ExecutionStatus.FAILED));
        verify(router, never()).dispatch(any(), any(), any());
    }

    @Test
    void statusTailAndKillGoToTheRunnerWithTheProjectAsScope() {
        when(runner.call(any(), eq("exec.status"), any())).thenReturn(Map.of("status", "RUNNING"));
        when(runner.call(any(), eq("exec.tail"), any())).thenReturn(Map.of("lines", List.of("x")));

        assertThat(jobs.status("acme", "p1", "j9")).containsEntry("status", "RUNNING");
        assertThat(jobs.tail("acme", "p1", "j9", 5, "STDERR")).containsKey("lines");

        ArgumentCaptor<ToolInvocationContext> scope = ArgumentCaptor.forClass(ToolInvocationContext.class);
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(runner).call(scope.capture(), eq("exec.tail"), args.capture());
        assertThat(scope.getValue().projectId()).isEqualTo("p1");
        assertThat(args.getValue())
                .containsEntry("id", "j9")
                .containsEntry("n", 5)
                .containsEntry("stream", "stderr");
    }
}
