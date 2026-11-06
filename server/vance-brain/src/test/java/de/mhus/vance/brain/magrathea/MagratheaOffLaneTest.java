package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A call to an outside service runs off the project lane: {@code execute} returns while the call is still
 * going, the result comes back through the completion event, a project has at most so many calls in flight,
 * and a call that is cancelled leaves its task to whoever cancelled it.
 */
class MagratheaOffLaneTest {

    private static final String YAML = """
            start: call
            states:
              call:
                type: tool_task
                tool: slow
                params: {}
                on:
                  success: done
              done:
                type: terminal
                outcome: success
            """;

    private final MagratheaJournalService journalService = mock(MagratheaJournalService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progress = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.shared.thinkprocess.ThinkProcessService> processes =
            mock(ObjectProvider.class);

    private final BlockingCall call = new BlockingCall();

    private MagratheaTaskExecutor executor(int maxConcurrentCalls) {
        MagratheaProperties properties = new MagratheaProperties();
        properties.setMaxConcurrentCalls(maxConcurrentCalls);
        ResolvedMagratheaWorkflow workflow = MagratheaWorkflowLoader.parseYaml("demo", YAML);
        when(workflowLoader.validateYaml(any(), any())).thenReturn(workflow);
        when(projector.projectVars(any(), any(), any())).thenReturn(Map.of());
        when(journalService.readLast(eq("t"), eq("p"), eq("run-1"), eq(StartRecord.class)))
                .thenReturn(Optional.of(StartRecord.builder()
                        .workflowName("demo")
                        .definitionYaml(YAML)
                        .build()));
        return new MagratheaTaskExecutor(
                journalService,
                projector,
                workflowLoader,
                eventBus,
                taskService,
                List.of(call),
                progress,
                processes,
                properties);
    }

    @Test
    void execute_returnsWhileTheCallIsStillGoing_andTheResultComesBackAsAnEvent() throws Exception {
        MagratheaTaskExecutor executor = executor(5);

        executor.execute(task("task-1"));

        // The lane thread is back already; the call has not finished.
        assertThat(call.started.await(2, TimeUnit.SECONDS)).isTrue();
        verify(eventBus, never()).publish(any());
        assertThat(executor.isInFlight("task-1")).isTrue();

        call.release.countDown();

        ArgumentCaptor<TaskCompletedEvent> event = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus, timeout(2000)).publish(event.capture());
        assertThat(event.getValue().taskId()).isEqualTo("task-1");
        assertThat(event.getValue().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    @Test
    void aProjectHasAtMostSoManyCallsInFlight() throws Exception {
        MagratheaTaskExecutor executor = executor(2);

        for (int i = 1; i <= 5; i++) executor.execute(task("task-" + i));

        // Two are calling; the other three wait for a place without holding the lane.
        assertThat(call.awaitRunning(2, 2000)).isTrue();
        Thread.sleep(300);
        assertThat(call.running.get()).isEqualTo(2);
        assertThat(call.peak.get()).isEqualTo(2);

        call.release.countDown();
        verify(eventBus, timeout(3000).times(5)).publish(any());
        assertThat(call.peak.get()).isEqualTo(2);
    }

    @Test
    void aLimitOfZeroMeansNoLimit() throws Exception {
        MagratheaTaskExecutor executor = executor(0);

        for (int i = 1; i <= 4; i++) executor.execute(task("task-" + i));

        assertThat(call.awaitRunning(4, 2000)).isTrue();
        call.release.countDown();
        verify(eventBus, timeout(3000).times(4)).publish(any());
    }

    @Test
    void aCancelledCallIsInterrupted_andItsResultIsDropped() throws Exception {
        MagratheaTaskExecutor executor = executor(5);
        executor.execute(task("task-1"));
        assertThat(call.started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.cancelInFlight("task-1")).isTrue();

        // Interrupted, it ends — and whoever cancelled it has decided the task, so nothing is published.
        assertThat(call.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        verify(eventBus, never()).publish(any());
        assertThat(executor.isInFlight("task-1")).isFalse();
    }

    @Test
    void aCallThatIsNotRunningCannotBeCancelled() {
        assertThat(executor(5).cancelInFlight("nobody")).isFalse();
    }

    @Test
    void aCallThatThrowsFailsItsTaskThroughTheEvent() {
        MagratheaTaskExecutor executor = executor(5);
        call.fail = true;

        executor.execute(task("task-1"));

        ArgumentCaptor<TaskCompletedEvent> event = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(eventBus, timeout(2000)).publish(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo("technical_error");
    }

    private static MagratheaTaskDocument task(String id) {
        return MagratheaTaskDocument.builder()
                .id(id)
                .tenantId("t")
                .projectId("p")
                .workflowRunId("run-1")
                .workflowName("demo")
                .stateName("call")
                .taskType(MagratheaTaskType.TOOL_TASK)
                .status(MagratheaTaskStatus.CLAIMED)
                .build();
    }

    /** A tool call that waits until it is released, and counts how many wait at once. */
    private static final class BlockingCall implements MagratheaTypeExecutor {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        volatile boolean fail;

        @Override
        public MagratheaTaskType type() {
            return MagratheaTaskType.TOOL_TASK;
        }

        @Override
        public boolean runsOffLane() {
            return true;
        }

        @Override
        public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
            if (fail) throw new IllegalStateException("boom");
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                running.decrementAndGet();
            }
            return Optional.of(TaskOutcome.success());
        }

        boolean awaitRunning(int count, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + millis * 1_000_000;
            while (System.nanoTime() < deadline) {
                if (running.get() >= count) return true;
                Thread.sleep(10);
            }
            return false;
        }
    }
}
