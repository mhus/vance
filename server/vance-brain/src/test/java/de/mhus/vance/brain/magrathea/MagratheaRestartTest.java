package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.shared.magrathea.MagratheaDebugSetup;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rerun and retry from a state: a new run, linked to the old one, that begins with
 * the variables the old run had on entering the state — replayed from its journal,
 * not taken from its end state.
 *
 * <p>The old runs are written straight into a small journal fake, because what
 * matters is what a restart reads back from one that has ended.
 */
class MagratheaRestartTest {

    private static final String YAML = """
            description: restart demo
            start: a
            states:
              a:
                type: condition_task
                transitions:
                  - else: b
              b:
                type: condition_task
                transitions:
                  - else: c
              c:
                type: terminal
                outcome: success
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class),
            org.mockito.Mockito.mock(MagratheaRunCost.class));

    private final Map<String, List<MagratheaJournalEntry>> entriesByRun = new HashMap<>();
    private final Map<MagratheaJournalEntry, JournalRecord> recordOf = new IdentityHashMap<>();
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
            MagratheaJournalEntry entry = MagratheaJournalEntry.builder().build();
            entriesByRun
                    .computeIfAbsent(inv.getArgument(2), k -> new ArrayList<>())
                    .add(entry);
            recordOf.put(entry, inv.getArgument(3));
            return entry;
        });
        when(journal.read(any(), any(), any()))
                .thenAnswer(inv -> entriesByRun.getOrDefault(inv.getArgument(2), List.of()));
        when(journal.toRecord(any(MagratheaJournalEntry.class)))
                .thenAnswer(inv -> Optional.ofNullable(recordOf.get(inv.<MagratheaJournalEntry>getArgument(0))));
        when(journal.readLast(any(), any(), any(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(3);
            List<MagratheaJournalEntry> entries = entriesByRun.getOrDefault(inv.getArgument(2), List.of());
            for (int i = entries.size() - 1; i >= 0; i--) {
                JournalRecord r = recordOf.get(entries.get(i));
                if (type.isInstance(r)) return Optional.of(r);
            }
            return Optional.empty();
        });
        when(tasks.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            t.setId("t" + (inserted.size() + 1));
            inserted.add(t);
            return t;
        });
        MagratheaWorkflowLoader real = new MagratheaWorkflowLoader(null);
        when(loader.validateYaml(any(), any())).thenAnswer(inv -> real.validateYaml("wf", YAML));
    }

    @Test
    void aRerunBeginsAtTheStartWithTheSameParametersAndPointsBack() {
        endedRun("old", Map.of("p", "1"), MagratheaRunStatus.FAILED, List.of("a", "b"), List.of());

        String fresh = service.restartRun("acme", "proj", "old", null, "bob", MagratheaDebugSetup.OFF);

        StartRecord start = start(fresh);
        assertThat(start.getRestartOf()).isEqualTo("old");
        assertThat(start.getRestartFrom()).isNull();
        assertThat(start.getParams()).containsEntry("p", "1");
        // Not a child: nobody waits for it.
        assertThat(start.getParentMagratheaProcessId()).isNull();
        assertThat(start.getStartedBy()).isEqualTo("bob");
        assertThat(inserted)
                .singleElement()
                .satisfies(t -> assertThat(t.getStateName()).isEqualTo("a"));
    }

    @Test
    void aRetryBeginsInTheStateWithTheVariablesFromBeforeItNotTheEndState() {
        // x was written before b, y after it entered: y belongs to what b is about to redo.
        endedRun(
                "old",
                Map.of(),
                MagratheaRunStatus.FAILED,
                List.of("a", "b"),
                List.of(new Var(1, "x", 1), new Var(2, "y", 2)));

        String fresh = service.restartRun("acme", "proj", "old", "b", "bob", MagratheaDebugSetup.OFF);

        assertThat(start(fresh).getRestartFrom()).isEqualTo("b");
        assertThat(inserted)
                .singleElement()
                .satisfies(t -> assertThat(t.getStateName()).isEqualTo("b"));
        assertThat(varKeys(fresh)).containsExactly("x");
    }

    @Test
    void aStateEnteredSeveralTimesRestoresTheVariablesOfItsLastEntry() {
        endedRun(
                "old",
                Map.of(),
                MagratheaRunStatus.FAILED,
                List.of("a", "b", "b"),
                List.of(new Var(1, "x", 1), new Var(2, "round", 1), new Var(3, "round", 2)));

        String fresh = service.restartRun("acme", "proj", "old", "b", "bob", MagratheaDebugSetup.OFF);

        // Entries: a(0) b(1) b(2); vars land before the entry with their index.
        assertThat(varKeys(fresh)).containsExactly("x", "round");
    }

    @Test
    void aRunThatStillGoesIsNotRestarted() {
        endedRun("old", Map.of(), MagratheaRunStatus.RUNNING, List.of("a"), List.of());

        assertThatThrownBy(() -> service.restartRun("acme", "proj", "old", null, "bob", MagratheaDebugSetup.OFF))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.restartableStates("acme", "proj", "old")).isEmpty();
    }

    @Test
    void aStateTheRunNeverEnteredOrATerminalIsRefused() {
        endedRun("old", Map.of(), MagratheaRunStatus.DONE, List.of("a", "b", "c"), List.of());

        assertThat(service.restartableStates("acme", "proj", "old")).hasValue(List.of("a", "b"));
        assertThatThrownBy(() -> service.restartRun("acme", "proj", "old", "c", "bob", MagratheaDebugSetup.OFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.restartRun("acme", "proj", "old", "nope", "bob", MagratheaDebugSetup.OFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inserted).isEmpty();
    }

    @Test
    void aRestartCanBeStartedInTheDebugger() {
        endedRun("old", Map.of(), MagratheaRunStatus.DONE, List.of("a", "b"), List.of());

        String fresh = service.restartRun(
                "acme", "proj", "old", "b", "bob", new MagratheaDebugSetup(true, java.util.Set.of()));

        assertThat(start(fresh).isDebug()).isTrue();
        assertThat(service.currentStatus("acme", "proj", fresh)).isEqualTo(MagratheaRunStatus.PAUSED);
    }

    @Test
    void anUnknownRunIsRefused() {
        assertThatThrownBy(() -> service.restartRun("acme", "proj", "ghost", null, "bob", MagratheaDebugSetup.OFF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A var written just before the state with this index is entered. */
    private record Var(int beforeEntry, String key, int value) {}

    /**
     * Write an old run into the journal: start, then each state entry, with the
     * variables placed in front of the entry they belong to ({@code beforeEntry} counts
     * from 0), then the status.
     */
    private void endedRun(
            String runId, Map<String, Object> params, MagratheaRunStatus status, List<String> entered, List<Var> vars) {
        append(
                runId,
                StartRecord.builder()
                        .workflowName("wf")
                        .definitionYaml(YAML)
                        .params(params)
                        .startedBy("alice")
                        .build());
        for (int i = 0; i < entered.size(); i++) {
            for (Var v : vars) {
                if (v.beforeEntry() == i) {
                    append(
                            runId,
                            VarRecord.builder()
                                    .key(v.key())
                                    .value(JSON.valueToTree(v.value()))
                                    .build());
                }
            }
            append(runId, StateEnteredRecord.builder().state(entered.get(i)).build());
        }
        if (status != MagratheaRunStatus.RUNNING) {
            append(runId, StatusRecord.builder().status(status).build());
        }
    }

    private void append(String runId, JournalRecord record) {
        journal.append("acme", "proj", runId, record);
    }

    private StartRecord start(String runId) {
        return latest(runId, StartRecord.class);
    }

    private List<String> varKeys(String runId) {
        List<String> keys = new ArrayList<>();
        for (MagratheaJournalEntry e : entriesByRun.getOrDefault(runId, List.of())) {
            if (recordOf.get(e) instanceof VarRecord v) keys.add(v.getKey());
        }
        return keys;
    }

    private <T extends JournalRecord> T latest(String runId, Class<T> type) {
        List<MagratheaJournalEntry> entries = entriesByRun.getOrDefault(runId, List.of());
        for (int i = entries.size() - 1; i >= 0; i--) {
            JournalRecord r = recordOf.get(entries.get(i));
            if (type.isInstance(r)) return type.cast(r);
        }
        throw new AssertionError("no " + type.getSimpleName() + " for run " + runId);
    }
}
