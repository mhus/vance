package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianCommandHandlerTest {

    private static final String TENANT = "acme";
    private static final String ACCOUNT = "_trillian-04506";

    @Mock
    TrillianInternalApi api;
    @Mock
    ThinkProcessService thinkProcessService;

    @InjectMocks
    TrillianCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(api.findPeer("control-proc")).thenReturn(Optional.of(peer(ThinkProcessStatus.IDLE)));
        when(api.snapshotPeerState(any())).thenAnswer(inv -> {
            ThinkProcessDocument p = inv.getArgument(0);
            return new TrillianInternalApi.PeerStateSnapshot(
                    p.getId(), p.getName(), p.getStatus(), 3L);
        });
        when(thinkProcessService.findBySession(TENANT, "sess-worker")).thenReturn(List.of());
    }

    @Test
    void handlerDoesNotUseTheAddressedLane() {
        // Everything it does targets the peer. Queueing behind Control's
        // turn would make info useless during a hang and stop useless
        // against the thing it should interrupt.
        assertThat(handler.runsOnLane()).isFalse();
    }

    @Test
    void bareCommand_defaultsToInfo() {
        EngineCommandResult result = handler.handle(control(), command(""));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(value(result)).containsKeys("control", "worker", "workers");
    }

    @Test
    void info_reportsTheGeneratedAccountName() {
        EngineCommandResult result = handler.handle(control(), command("info"));

        // The name is generated per session and appeared only in the log —
        // needing it is exactly why this command exists.
        assertThat(worker(result)).containsEntry("account", ACCOUNT);
    }

    @Test
    void info_reportsWorkerStatusAndInboxDepth() {
        EngineCommandResult result = handler.handle(control(), command("info"));

        assertThat(worker(result))
                .containsEntry("status", "IDLE")
                .containsEntry("pendingInbox", 3L);
        assertThat(result.message()).contains(ACCOUNT).contains("inbox 3");
    }

    @Test
    void info_listsSpawnedWorkersWithTheirTargetProject() {
        when(thinkProcessService.findBySession(TENANT, "sess-worker")).thenReturn(List.of(
                peer(ThinkProcessStatus.IDLE),
                taskWorker("count-md", "test1", ThinkProcessStatus.RUNNING)));

        EngineCommandResult result = handler.handle(control(), command("info"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers =
                (List<Map<String, Object>>) value(result).get("workers");
        // The loop itself is not a task worker; the target project is what
        // makes a cross-project spawn visible at a glance.
        assertThat(workers).hasSize(1);
        assertThat(workers.get(0))
                .containsEntry("name", "count-md")
                .containsEntry("projectId", "test1")
                .containsEntry("status", "RUNNING");
    }

    @Test
    void info_skipsClosedWorkers() {
        when(thinkProcessService.findBySession(TENANT, "sess-worker")).thenReturn(List.of(
                taskWorker("done-one", "test1", ThinkProcessStatus.CLOSED),
                taskWorker("live-one", "test1", ThinkProcessStatus.RUNNING)));

        EngineCommandResult result = handler.handle(control(), command("info"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers =
                (List<Map<String, Object>>) value(result).get("workers");
        assertThat(workers).singleElement()
                .satisfies(w -> assertThat(w).containsEntry("name", "live-one"));
    }

    @Test
    void stop_pausesThroughTheSharedApi() {
        when(api.pausePeer(any())).thenReturn(ThinkProcessStatus.PAUSED);

        EngineCommandResult result = handler.handle(control(), command("stop"));

        // Same call the user_stop tool makes — one implementation, two
        // entrances.
        verify(api).pausePeer(any());
        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("PAUSED");
    }

    @Test
    void stop_saysThatSpawnedWorkersKeepRunning() {
        when(api.pausePeer(any())).thenReturn(ThinkProcessStatus.PAUSED);

        EngineCommandResult result = handler.handle(control(), command("stop"));

        // Pausing the loop does not reach into workers already running —
        // silently implying otherwise would be the worse failure.
        assertThat(result.message()).contains("keep running");
    }

    @Test
    void continueCommand_resumesThroughTheSharedApi() {
        when(api.resumePeer(any())).thenReturn(ThinkProcessStatus.IDLE);

        assertThat(handler.handle(control(), command("continue")).outcome())
                .isEqualTo(EngineCommandOutcome.OK);
        verify(api).resumePeer(any());
    }

    @Test
    void resumeIsAnAliasForContinue() {
        when(api.resumePeer(any())).thenReturn(ThinkProcessStatus.IDLE);

        handler.handle(control(), command("resume"));

        verify(api).resumePeer(any());
    }

    // ──── attr ──────────────────────────────────────────────────────────

    @Test
    void attr_listsWhatTheWorkerCarries() {
        EngineCommandResult result = handler.handle(control(), command("attr"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) value(result).get("attributes");
        assertThat(attrs).containsEntry("persona", "dry Swabian");
        assertThat(result.message()).contains("persona");
    }

    @Test
    void attr_setTakesTheRestOfTheLineAsValue() {
        when(api.setPeerAttribute(any(), any(), any())).thenReturn(true);

        handler.handle(control(), command("attr set persona a dry Swabian who mumbles"));

        // No quoting for a value that is a sentence — the common case.
        verify(api).setPeerAttribute("control-proc", "persona", "a dry Swabian who mumbles");
    }

    @Test
    void attr_setGoesThroughTheSameApiAsTheTool() {
        when(api.setPeerAttribute(any(), any(), any())).thenReturn(true);

        assertThat(handler.handle(control(), command("attr set lang de")).outcome())
                .isEqualTo(EngineCommandOutcome.OK);
        // user_attr_set writes the same map through the same call, so a
        // value set by hand is indistinguishable from one Control set.
        verify(api).setPeerAttribute("control-proc", "lang", "de");
    }

    @Test
    void attr_setWithoutValue_explainsUsage() {
        EngineCommandResult result = handler.handle(control(), command("attr set persona"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("attr set <name> <value>");
        verify(api, never()).setPeerAttribute(any(), any(), any());
    }

    @Test
    void attr_deleteReportsWhetherAnythingWasThere() {
        when(api.removePeerAttribute("control-proc", "gone")).thenReturn(false);
        when(api.removePeerAttribute("control-proc", "persona")).thenReturn(true);

        assertThat(handler.handle(control(), command("attr del gone")).message())
                .contains("was not set");
        assertThat(handler.handle(control(), command("attr del persona")).message())
                .contains("Removed");
    }

    @Test
    void attr_clearReportsTheCount() {
        when(api.clearPeerAttributes("control-proc")).thenReturn(3);

        assertThat(handler.handle(control(), command("attr clear")).message()).contains("3");
    }

    @Test
    void attr_unknownSubcommand_listsWhatExists() {
        EngineCommandResult result = handler.handle(control(), command("attr frobnicate"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("set").contains("del").contains("clear");
    }

    // ──── queue ─────────────────────────────────────────────────────────

    @Test
    void queue_listsWhatIsWaitingWithItsKind() {
        when(api.listPending("worker-proc")).thenReturn(List.of(
                request("t-1", "count the markdown docs"),
                done("t-0")));

        EngineCommandResult result = handler.handle(control(), command("queue"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) value(result).get("pending");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("kind", "task_request")
                .containsEntry("taskId", "t-1")
                .containsEntry("description", "count the markdown docs");
        assertThat(rows.get(1)).containsEntry("kind", "task_done");
    }

    @Test
    void queue_separatesWaitingTasksFromOtherMessages() {
        when(api.listPending("worker-proc")).thenReturn(List.of(
                request("t-1", "a"), request("t-2", "b"), done("t-0")));

        EngineCommandResult result = handler.handle(control(), command("queue"));

        // A depth of 3 says nothing about whether anything is still to be
        // started — this does.
        assertThat(result.message()).contains("2 waiting task(s)").contains("1 other");
    }

    @Test
    void queue_abbreviatesLongDescriptions() {
        when(api.listPending("worker-proc"))
                .thenReturn(List.of(request("t-1", "x".repeat(200))));

        EngineCommandResult result = handler.handle(control(), command("queue"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) value(result).get("pending");
        assertThat((String) rows.get(0).get("description")).hasSizeLessThan(85).endsWith("...");
    }

    // ──── task ──────────────────────────────────────────────────────────

    @Test
    void task_queuesThroughTheSharedApi() {
        when(api.enqueueTask(any(), any(), any())).thenReturn(Optional.of("t-9"));

        EngineCommandResult result = handler.handle(
                control(), command("task count all markdown docs"));

        // Same call task_enqueue makes, so a hand-raised task is
        // indistinguishable from one Control raised.
        verify(api).enqueueTask(eq("control-proc"), any(), eq("count all markdown docs"));
        assertThat(result.message()).contains("t-9");
    }

    @Test
    void task_withoutDescription_explainsUsage() {
        EngineCommandResult result = handler.handle(control(), command("task"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("//trillian task <description>");
        verify(api, never()).enqueueTask(any(), any(), any());
    }

    @Test
    void task_dispatchFailure_isReported() {
        when(api.enqueueTask(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(handler.handle(control(), command("task do a thing")).outcome())
                .isEqualTo(EngineCommandOutcome.ERROR);
    }

    // ──── clear ─────────────────────────────────────────────────────────

    @Test
    void clear_dropsWaitingTasksButKeepsResults() {
        when(api.clearPending("worker-proc", true))
                .thenReturn(new TrillianInternalApi.ClearResult(2, 2, 0));

        EngineCommandResult result = handler.handle(control(), command("clear"));

        // Dropping a task_done would lose work that already happened —
        // the loop never learns the outcome and the task stays open.
        verify(api).clearPending("worker-proc", true);
        assertThat(result.message()).contains("2 waiting task(s)").contains("left in place");
    }

    @Test
    void clearAll_dropsEverythingAndSaysSo() {
        when(api.clearPending("worker-proc", false))
                .thenReturn(new TrillianInternalApi.ClearResult(3, 2, 1));

        EngineCommandResult result = handler.handle(control(), command("clear all"));

        verify(api).clearPending("worker-proc", false);
        assertThat(result.message()).contains("results included");
        assertThat(value(result)).containsEntry("other", 1);
    }

    @Test
    void withoutAPairedWorker_everySubcommandRefuses() {
        when(api.findPeer("control-proc")).thenReturn(Optional.empty());

        EngineCommandResult result = handler.handle(control(), command("stop"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        verify(api, never()).pausePeer(any());
    }

    @Test
    void unknownSubcommand_listsWhatExists() {
        EngineCommandResult result = handler.handle(control(), command("explode"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("info").contains("stop").contains("clear");
        verify(api, never()).pausePeer(any());
    }

    @Test
    void pauseFailure_isReportedNotThrown() {
        when(api.pausePeer(any())).thenThrow(new IllegalStateException("lane wedged"));

        EngineCommandResult result = handler.handle(control(), command("stop"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("lane wedged");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static TrillianInternalApi.PendingEntry request(String taskId, String description) {
        return new TrillianInternalApi.PendingEntry(
                "m-" + taskId, TrillianInternalApi.TASK_EVENT_REQUEST, taskId, description,
                java.time.Instant.now());
    }

    private static TrillianInternalApi.PendingEntry done(String taskId) {
        return new TrillianInternalApi.PendingEntry(
                "m-" + taskId, TrillianInternalApi.TASK_EVENT_DONE, taskId, "finished",
                java.time.Instant.now());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static EngineCommand command(String text) {
        return new EngineCommand("trillian", Map.of("text", text));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(EngineCommandResult result) {
        return (Map<String, Object>) result.value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> worker(EngineCommandResult result) {
        return (Map<String, Object>) value(result).get("worker");
    }

    private static ThinkProcessDocument control() {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("control-proc");
        doc.setTenantId(TENANT);
        doc.setSessionId("sess-control");
        doc.setProjectId("trillian-test");
        doc.setStatus(ThinkProcessStatus.IDLE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, "0");
        doc.setEngineParams(params);
        return doc;
    }

    private static ThinkProcessDocument peer(ThinkProcessStatus status) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("worker-proc");
        doc.setName("trillian-user-loop");
        doc.setTenantId(TENANT);
        doc.setSessionId("sess-worker");
        doc.setProjectId("trillian-test");
        doc.setStatus(status);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, ACCOUNT);
        params.put(TrillianInternalApi.PARAM_ATTRIBUTES,
                new LinkedHashMap<>(Map.of("persona", "dry Swabian")));
        doc.setEngineParams(params);
        return doc;
    }

    private static ThinkProcessDocument taskWorker(
            String name, String projectId, ThinkProcessStatus status) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("w-" + name);
        doc.setName(name);
        doc.setTenantId(TENANT);
        doc.setSessionId("sess-worker");
        doc.setProjectId(projectId);
        doc.setThinkEngine("frankie");
        doc.setStatus(status);
        return doc;
    }
}
