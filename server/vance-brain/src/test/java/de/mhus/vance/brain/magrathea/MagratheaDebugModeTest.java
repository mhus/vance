package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.DebugRecord;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Debug mode: a run started with the debug flag halts before each state and
 * waits to be stepped; a resume ends the halting.
 *
 * <p>The journal is a small fake over the list of appended records, so the
 * service reads back what it wrote — which is what the halting depends on
 * (the status, the start flag, the latest {@link DebugRecord}).
 */
class MagratheaDebugModeTest {

    private static final String YAML = """
            description: debug demo
            start: first
            states:
              first:
                type: condition_task
                transitions:
                  - else: second
              second:
                type: condition_task
                transitions:
                  - else: done
              done:
                type: terminal
                outcome: success
            """;

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader loader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService tasks = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager lanes = mock(MagratheaProjectLaneManager.class);
    private final MagratheaLocalDispatch dispatch = mock(MagratheaLocalDispatch.class);

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            loader,
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journal,
            tasks,
            lanes,
            mock(MagratheaTaskExecutor.class),
            dispatch,
            mock(de.mhus.vance.shared.magrathea.MagratheaStateProjector.class),
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class));

    private final List<JournalRecord> appended = new ArrayList<>();
    private final List<MagratheaTaskDocument> inserted = new ArrayList<>();

    @BeforeEach
    void wire() {
        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .when(lanes)
                .submitTracked(any(), any());
        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return null;
                })
                .when(lanes)
                .submit(any(), any());

        when(journal.append(any(), any(), any(), any(JournalRecord.class))).thenAnswer(inv -> {
            appended.add(inv.getArgument(3));
            return MagratheaJournalEntry.builder().build();
        });
        when(journal.appendIfAbsent(any(), any(), any(), any(), any(JournalRecord.class)))
                .thenAnswer(inv -> {
                    appended.add(inv.getArgument(4));
                    return Optional.of(MagratheaJournalEntry.builder().build());
                });
        // The fake: the latest appended record of the asked type.
        when(journal.readLast(any(), any(), any(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(3);
            for (int i = appended.size() - 1; i >= 0; i--) {
                if (type.isInstance(appended.get(i))) return Optional.of(appended.get(i));
            }
            return Optional.empty();
        });

        when(tasks.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            t.setId("t" + (inserted.size() + 1));
            inserted.add(t);
            return t;
        });
        when(tasks.findById(any()))
                .thenAnswer(inv -> inserted.stream()
                        .filter(t -> inv.getArgument(0).equals(t.getId()))
                        .findFirst());
        when(tasks.findByRun(any())).thenAnswer(inv -> List.copyOf(inserted));
        when(tasks.releaseRun(any())).thenAnswer(inv -> {
            long n = 0;
            for (MagratheaTaskDocument t : inserted) {
                if (t.getStatus() == MagratheaTaskStatus.HELD) {
                    t.setStatus(MagratheaTaskStatus.PENDING);
                    n++;
                }
            }
            return n;
        });

        MagratheaWorkflowLoader real = new MagratheaWorkflowLoader(null);
        when(loader.load(any(), any(), eq("demo"))).thenAnswer(inv -> Optional.of(real.validateYaml("demo", YAML)));
        when(loader.validateYaml(eq("demo"), any())).thenAnswer(inv -> real.validateYaml("demo", YAML));
    }

    @Test
    void aDebugRunHaltsBeforeItsFirstState() {
        String runId = startDebug();

        assertThat(appended.get(0))
                .isInstanceOfSatisfying(
                        StartRecord.class, s -> assertThat(s.isDebug()).isTrue());
        assertThat(inserted).singleElement().satisfies(t -> {
            assertThat(t.getStateName()).isEqualTo("first");
            assertThat(t.getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        });
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("before state 'first'");
    }

    @Test
    void aNormalRunIsNotHalted() {
        service.start("acme", "proj", "demo", Map.of(), "alice");

        assertThat(inserted)
                .singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(MagratheaTaskStatus.PENDING));
        assertThat(appended).noneMatch(r -> r instanceof StatusRecord);
    }

    @Test
    void stepReleasesTheStateAndHaltsAgainBeforeTheNext() {
        String runId = startDebug();

        assertThat(service.stepRun("acme", "proj", runId)).isTrue();

        MagratheaTaskDocument first = inserted.get(0);
        assertThat(first.getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        // Released tasks go to the lane at once, not at the claimer's next scan.
        verify(dispatch, atLeastOnce()).dispatch(first);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.RUNNING);

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStateName()).isEqualTo("second");
        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(lastStatus().getReason()).contains("before state 'second'");
    }

    @Test
    void resumeEndsTheHaltingSoTheRunGoesOn() {
        String runId = startDebug();

        assertThat(service.resumeRun("acme", "proj", runId)).isTrue();

        // The stepping is switched off before the queue is released, so no
        // completion can halt the run again in between.
        var order = inOrder(journal, tasks);
        order.verify(journal).append(any(), any(), any(), any(DebugRecord.class));
        order.verify(tasks).releaseRun(runId);
        assertThat(appended)
                .anySatisfy(r -> assertThat(r)
                        .isInstanceOfSatisfying(
                                DebugRecord.class,
                                d -> assertThat(d.isStepping()).isFalse()));

        service.handleCompletion(completed(runId, "t1", "first", "second"));

        assertThat(inserted.get(1).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        assertThat(service.currentStatus("acme", "proj", runId)).isEqualTo(MagratheaRunStatus.RUNNING);
    }

    @Test
    void aStepAfterAResumePutsTheHaltingBack() {
        String runId = startDebug();
        service.resumeRun("acme", "proj", runId);
        service.pauseRun("acme", "proj", runId);

        assertThat(service.stepRun("acme", "proj", runId)).isTrue();

        assertThat(appended.stream().filter(r -> r instanceof DebugRecord).reduce((a, b) -> b))
                .get()
                .isInstanceOfSatisfying(
                        DebugRecord.class, d -> assertThat(d.isStepping()).isTrue());
    }

    @Test
    void aRunThatIsNotHaltedCannotBeStepped() {
        String runId = startDebug();
        service.stepRun("acme", "proj", runId);

        // RUNNING now: the button came from an older snapshot.
        assertThat(service.stepRun("acme", "proj", runId)).isFalse();
    }

    @Test
    void aNormalPausedRunCannotBeStepped() {
        String runId = service.start("acme", "proj", "demo", Map.of(), "alice");
        service.pauseRun("acme", "proj", runId);

        assertThat(service.stepRun("acme", "proj", runId)).isFalse();
        assertThat(appended).noneMatch(r -> r instanceof DebugRecord);
    }

    // ──────────────────── helpers ────────────────────

    private String startDebug() {
        return service.start(
                "acme",
                "proj",
                "demo",
                Map.of(),
                "alice",
                /* parent */ null, /* parentState */
                null,
                MagratheaRunBinding.headless(), /* debug */
                true);
    }

    private StatusRecord lastStatus() {
        return appended.stream()
                .filter(r -> r instanceof StatusRecord)
                .map(r -> (StatusRecord) r)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private static TaskCompletedEvent completed(String runId, String taskId, String state, String next) {
        return new TaskCompletedEvent(
                "acme",
                "proj",
                runId,
                taskId,
                state,
                MagratheaTaskType.CONDITION_TASK,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                null,
                null,
                5L,
                next);
    }
}
