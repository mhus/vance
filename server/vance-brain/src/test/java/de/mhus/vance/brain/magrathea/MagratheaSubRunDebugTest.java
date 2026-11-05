package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.magrathea.MagratheaDebugSetup;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.BreakpointsRecord;
import de.mhus.vance.shared.magrathea.journal.DebugRecord;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A sub-run is debugged along with the run that starts it: it steps while the
 * parent steps, and takes the parent's breakpoints for its workflow — which a
 * breakpoint set on one sub-run feeds, so every element of a for-each halts at
 * the same state.
 *
 * <p>The journal fake keeps one list per run, because parent and sub-run are
 * read apart here.
 */
class MagratheaSubRunDebugTest {

    private static final String PARENT_YAML = """
            description: parent
            start: call
            states:
              call:
                type: condition_task
                transitions:
                  - else: done
              done:
                type: terminal
                outcome: success
            """;

    private static final String CHILD_YAML = """
            description: child
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

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            loader,
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journal,
            tasks,
            lanes,
            mock(MagratheaTaskExecutor.class),
            mock(MagratheaLocalDispatch.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaStateProjector.class),
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class));

    private final Map<String, List<JournalRecord>> byRun = new HashMap<>();
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
            byRun.computeIfAbsent(inv.getArgument(2), k -> new ArrayList<>()).add(inv.getArgument(3));
            return MagratheaJournalEntry.builder().build();
        });
        when(journal.readLast(any(), any(), any(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(3);
            List<JournalRecord> records = byRun.getOrDefault(inv.getArgument(2), List.of());
            for (int i = records.size() - 1; i >= 0; i--) {
                if (type.isInstance(records.get(i))) return Optional.of(records.get(i));
            }
            return Optional.empty();
        });
        when(tasks.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            t.setId("t" + (inserted.size() + 1));
            inserted.add(t);
            return t;
        });
        when(tasks.findByRun(any()))
                .thenAnswer(inv -> inserted.stream()
                        .filter(t -> inv.getArgument(0).equals(t.getWorkflowRunId()))
                        .toList());
        when(tasks.releaseRun(any())).thenAnswer(inv -> {
            long n = 0;
            for (MagratheaTaskDocument t : inserted) {
                if (inv.getArgument(0).equals(t.getWorkflowRunId()) && t.getStatus() == MagratheaTaskStatus.HELD) {
                    t.setStatus(MagratheaTaskStatus.PENDING);
                    n++;
                }
            }
            return n;
        });
        MagratheaWorkflowLoader real = new MagratheaWorkflowLoader(null);
        when(loader.load(any(), any(), eq("parent")))
                .thenAnswer(inv -> Optional.of(real.validateYaml("parent", PARENT_YAML)));
        when(loader.validateYaml(eq("parent"), any())).thenAnswer(inv -> real.validateYaml("parent", PARENT_YAML));
        when(loader.load(any(), any(), eq("child")))
                .thenAnswer(inv -> Optional.of(real.validateYaml("child", CHILD_YAML)));
        when(loader.validateYaml(eq("child"), any())).thenAnswer(inv -> real.validateYaml("child", CHILD_YAML));
    }

    @Test
    void aSubRunOfASteppingParentStepsAndHaltsBeforeItsFirstState() {
        String parent = startParent(new MagratheaDebugSetup(true, Set.of()));

        String child = startChild(parent);

        assertThat(start(child).isDebug()).isTrue();
        assertThat(service.currentStatus("acme", "proj", child)).isEqualTo(MagratheaRunStatus.PAUSED);
        assertThat(taskOf(child).getStatus()).isEqualTo(MagratheaTaskStatus.HELD);
    }

    @Test
    void aSubRunOfAnOrdinaryRunIsOrdinary() {
        String parent = startParent(MagratheaDebugSetup.OFF);

        String child = startChild(parent);

        assertThat(MagratheaDebugSetup.isDebuggable(start(child))).isFalse();
        assertThat(taskOf(child).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
    }

    @Test
    void aSubRunTakesTheParentsBreakpointsForItsWorkflow() {
        String parent = startParent(
                new MagratheaDebugSetup(false, Set.of(), Map.of("child", Set.of("second"), "other", Set.of("x"))));

        String child = startChild(parent);

        assertThat(start(child).isDebug()).isFalse();
        assertThat(start(child).getBreakpoints()).containsExactly("second");
        assertThat(taskOf(child).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
        // The whole map goes on, for runs deeper down.
        assertThat(start(child).getSubBreakpoints()).containsKeys("child", "other");
    }

    @Test
    void breakpointNamesTheWorkflowNoLongerHasAreDropped() {
        String parent = startParent(new MagratheaDebugSetup(
                false, Set.of(), Map.of("child", new LinkedHashSet<>(List.of("gone", "second")))));

        String child = startChild(parent);

        assertThat(start(child).getBreakpoints()).containsExactly("second");
    }

    @Test
    void aBreakpointSetOnASubRunHoldsForTheNextOneOfTheWorkflow() {
        String parent = startParent(new MagratheaDebugSetup(true, Set.of()));
        String first = startChild(parent);

        assertThat(service.setBreakpoints("acme", "proj", first, List.of("second")))
                .isTrue();
        String second = startChild(parent);

        assertThat(start(second).getBreakpoints()).containsExactly("second");
        assertThat(latest(parent, BreakpointsRecord.class).getWorkflows()).containsEntry("child", Set.of("second"));
    }

    @Test
    void clearingTheBreakpointsOfASubRunClearsThemForTheWorkflow() {
        String parent = startParent(new MagratheaDebugSetup(true, Set.of()));
        String first = startChild(parent);
        service.setBreakpoints("acme", "proj", first, List.of("second"));

        service.setBreakpoints("acme", "proj", first, List.of());

        assertThat(latest(parent, BreakpointsRecord.class).getWorkflows()).doesNotContainKey("child");
    }

    @Test
    void resumingASubRunEndsTheSteppingOfTheParentToo() {
        String parent = startParent(new MagratheaDebugSetup(true, Set.of()));
        String first = startChild(parent);

        assertThat(service.resumeRun("acme", "proj", first)).isTrue();
        String second = startChild(parent);

        // Without this the second element would step again, inherited from a
        // parent that still was.
        assertThat(latest(parent, DebugRecord.class).isStepping()).isFalse();
        assertThat(MagratheaDebugSetup.isDebuggable(start(second))).isFalse();
        assertThat(taskOf(second).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
    }

    private String startParent(MagratheaDebugSetup debug) {
        return service.start(
                "acme", "proj", "parent", Map.of(), "alice", null, null, MagratheaRunBinding.headless(), debug);
    }

    private String startChild(String parent) {
        return service.start(
                "acme", "proj", "child", Map.of(), "alice", parent, "call", MagratheaRunBinding.headless());
    }

    private StartRecord start(String runId) {
        return latest(runId, StartRecord.class);
    }

    private <T extends JournalRecord> T latest(String runId, Class<T> type) {
        List<JournalRecord> records = byRun.getOrDefault(runId, List.of());
        for (int i = records.size() - 1; i >= 0; i--) {
            if (type.isInstance(records.get(i))) return type.cast(records.get(i));
        }
        throw new AssertionError("no " + type.getSimpleName() + " for run " + runId);
    }

    private MagratheaTaskDocument taskOf(String runId) {
        return inserted.stream()
                .filter(t -> runId.equals(t.getWorkflowRunId()))
                .findFirst()
                .orElseThrow();
    }
}
