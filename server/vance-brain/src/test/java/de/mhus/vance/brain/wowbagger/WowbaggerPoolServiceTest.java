package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pool's rotation mechanics against mocked services and a real source
 * file: start → workers rotate chunks → per-chunk publish → finish with
 * merge and wakeup (planning/wowbagger-engine.md §4a.2).
 */
class WowbaggerPoolServiceTest {

    private static final String PROC_ID = "proc0001";
    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";

    @TempDir
    Path tempDir;

    private ThinkProcessService thinkProcessService;
    private WorkspaceService workspaceService;
    private WorkTargetService workTargetService;
    private LightLlmService lightLlmService;
    private DocumentService documentService;
    private ThinkProcessDocument process;
    private WowbaggerPoolService pool;
    private de.mhus.vance.shared.settings.SettingService settingService;
    private final Map<String, DocumentDocument> docsByPath = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> docContents = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        thinkProcessService = mock(ThinkProcessService.class);
        lightLlmService = mock(LightLlmService.class);
        documentService = mock(DocumentService.class);
        workspaceService = mock(WorkspaceService.class);
        workTargetService = mock(WorkTargetService.class);
        ChatMessageService chatLog = mock(ChatMessageService.class);
        MetricService metricService = new MetricService(new SimpleMeterRegistry());
        ObjectMapper om = JsonMapper.builder().build();
        de.mhus.vance.brain.recipe.RecipeResolver recipeResolver =
                mock(de.mhus.vance.brain.recipe.RecipeResolver.class);
        de.mhus.vance.brain.ai.AiModelResolver aiModelResolver = mock(de.mhus.vance.brain.ai.AiModelResolver.class);
        settingService = mock(de.mhus.vance.shared.settings.SettingService.class);

        pool = new WowbaggerPoolService(
                thinkProcessService,
                lightLlmService,
                documentService,
                workspaceService,
                workTargetService,
                metricService,
                om,
                chatLog,
                recipeResolver,
                aiModelResolver,
                settingService);

        // Worker-model resolution: recipe → params.model spec → resolved model.
        de.mhus.vance.brain.recipe.ResolvedRecipe workerRecipe = mock(de.mhus.vance.brain.recipe.ResolvedRecipe.class);
        lenient().when(workerRecipe.params()).thenReturn(Map.of("model", "spec-fast"));
        lenient()
                .when(recipeResolver.resolve(eq(TENANT), eq(PROJECT), anyString()))
                .thenReturn(Optional.of(workerRecipe));
        lenient()
                .when(aiModelResolver.resolveOrDefault(eq("default:spec-fast"), eq(TENANT), eq(PROJECT), eq(PROC_ID)))
                .thenReturn(new de.mhus.vance.brain.ai.AiModelResolver.Resolved(
                        "openai", "openai", "deepseek-v4-flash-0731", false));
        lenient()
                .when(workspaceService.createRootDir(
                        org.mockito.ArgumentMatchers.any(de.mhus.vance.shared.workspace.RootDirSpec.class)))
                .thenAnswer(inv -> {
                    de.mhus.vance.shared.workspace.RootDirSpec spec = inv.getArgument(0);
                    var handle = mock(de.mhus.vance.shared.workspace.RootDirHandle.class);
                    lenient().when(handle.getDirName()).thenReturn(spec.getLabelHint());
                    return handle;
                });
        // Operator allowlist: the cheap fast tier is approved.
        lenient()
                .when(settingService.getStringValue(
                        eq(TENANT),
                        eq(de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT),
                        eq(PROJECT),
                        eq(WowbaggerPoolService.ALLOWED_MODELS_KEY)))
                .thenReturn("*deepseek*");

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId(TENANT);
        process.setProjectId(PROJECT);
        process.setSessionId("sess-1");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setEngineParams(new LinkedHashMap<>());

        // A configured structure: 5 records, 2 per chunk, 2 threads.
        WowbaggerState state = new WowbaggerState();
        state.setTask("classify each record");
        state.setSourcePath("input.txt");
        state.setInputFormat("lines");
        state.setOutputFormat("lines");
        state.setChunkSize(2);
        state.setThreadsDesired(2);
        state.setChunkRetries(0);
        state.setWakeEveryRecords(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(WowbaggerPoolService.ENGINE_STATE_KEY, om.convertValue(state, Map.class));
        process.setEngineParams(params);

        Files.write(tempDir.resolve("input.txt"), List.of("r1", "r2", "r3", "r4", "r5"), StandardCharsets.UTF_8);

        lenient().when(workTargetService.current(process)).thenReturn(new WorkTarget(WorkTargetKind.WORK, "data"));
        lenient()
                .when(workspaceService.resolve(eq(TENANT), eq(PROJECT), anyString(), anyString()))
                .thenAnswer(inv -> tempDir.resolve(inv.getArgument(3, String.class)));
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        lenient()
                .when(thinkProcessService.replaceEngineParams(eq(PROC_ID), any()))
                .thenAnswer(inv -> {
                    process.setEngineParams(inv.getArgument(1, Map.class));
                    return true;
                });
        lenient()
                .when(documentService.findByPath(eq(TENANT), eq(PROJECT), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(docsByPath.get(inv.getArgument(2, String.class))));
        lenient()
                .when(documentService.createText(
                        anyString(), anyString(), anyString(), any(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(2, String.class);
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("doc-" + path);
                    doc.setPath(path);
                    docsByPath.put(path, doc);
                    docContents.put(doc.getId(), inv.getArgument(5, String.class));
                    return doc;
                });
        lenient()
                .when(documentService.readContent(any()))
                .thenAnswer(inv -> docContents.get(
                        inv.getArgument(0, DocumentDocument.class).getId()));
    }

    private void stubEchoWorker() {
        AtomicInteger call = new AtomicInteger();
        when(lightLlmService.call(any(LightLlmRequest.class))).thenAnswer(inv -> {
            LightLlmRequest req = inv.getArgument(0);
            String prompt = req.getUserPrompt();
            int marker = prompt.indexOf("in order)\n");
            int count = marker < 0
                    ? 1
                    : (int) prompt.substring(marker + "in order)\n".length())
                            .lines()
                            .count();
            StringBuilder reply = new StringBuilder();
            for (int i = 0; i < Math.max(count, 1); i++) {
                if (i > 0) {
                    reply.append('\n');
                }
                reply.append("out-").append(call.incrementAndGet());
            }
            return reply.toString();
        });
    }

    private WowbaggerState persistedState() {
        Object raw = process.getEngineParams().get(WowbaggerPoolService.ENGINE_STATE_KEY);
        if (raw == null) {
            return new WowbaggerState();
        }
        return WowbaggerPoolService.normalize(JsonMapper.builder().build().convertValue(raw, WowbaggerState.class));
    }

    @Test
    void rotationPublishesPerChunkAndMergesAtFinish() throws IOException {
        stubEchoWorker();

        pool.start(process);

        // 5 records / chunk 2 = 3 chunk docs + the merged result doc.
        verify(documentService, timeout(20_000).times(4))
                .createText(eq(TENANT), eq(PROJECT), anyString(), any(), any(), anyString(), any(), any());
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        WowbaggerState s = persistedState();
        assertThat(s.getRecordsDone()).isEqualTo(5);
        assertThat(s.getPointer()).isEqualTo(5);
        assertThat(s.isFinished()).isTrue();
        assertThat(s.getFailedChunks()).isEmpty();
        // start/finish wakeups reached the agent's pending queue.
        verify(thinkProcessService, atLeast(2)).appendPending(eq(PROC_ID), any(), anyString());
    }

    @Test
    void exhaustedRetriesLandInTheFailureLedgerWithCooldownWakeups() throws IOException {
        when(lightLlmService.call(any(LightLlmRequest.class)))
                .thenThrow(new de.mhus.vance.brain.ai.light.LightLlmException("provider down"));

        pool.start(process);

        waitFor(20_000, () -> !persistedState().getFailedChunks().isEmpty());
        // The runner keeps rotating: chunks 0–2 all fail into the ledger.
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        WowbaggerState s = persistedState();
        assertThat(s.getFailedChunks()).hasSize(3);
        assertThat(s.getRecordsDone()).isZero();
        // Every failed chunk counts — the counter tracks all of them …
        assertThat(s.getFailureCount()).isEqualTo(3);
        // … but the default 300s cooldown throttles the wakeups to exactly one.
        assertThat(pendingNotes("failed after")).hasSize(1);
        assertThat(pendingNotes("failure(s) since your last ack")).hasSize(1);
    }

    @Test
    void reRunFailedRequeuesWithFreshBudgetAndResetsTheCounter() throws IOException {
        when(lightLlmService.call(any(LightLlmRequest.class)))
                .thenThrow(new de.mhus.vance.brain.ai.light.LightLlmException("provider down"));
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        assertThat(persistedState().getFailedChunks()).hasSize(3);
        assertThat(persistedState().getFailureCount()).isEqualTo(3);

        // The agent decides: fix and re-run — the worker now succeeds.
        stubEchoWorker();
        pool.reRunFailed(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        WowbaggerState s = persistedState();
        assertThat(s.getFailedChunks()).isEmpty();
        assertThat(s.getRetryQueue()).isEmpty();
        assertThat(s.getRecordsDone()).isEqualTo(5);
        // The re-run is the agent's acknowledgement — the counter resets.
        assertThat(s.getFailureCount()).isZero();
    }

    @Test
    void startRefusesUnapprovedWorkerModelAndNamesIt() throws IOException {
        // The operator allowlist does not cover the resolved model — the gate
        // must refuse before a single record is processed and name the model.
        lenient()
                .when(settingService.getStringValue(
                        eq(TENANT),
                        eq(de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT),
                        eq(PROJECT),
                        eq(WowbaggerPoolService.ALLOWED_MODELS_KEY)))
                .thenReturn("*cheap-model*");

        assertThatThrownBy(() -> pool.start(process))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not approved")
                .hasMessageContaining("openai:deepseek-v4-flash-0731")
                .hasMessageContaining(WowbaggerPoolService.ALLOWED_MODELS_KEY);

        assertThat(pool.isRunning(PROC_ID)).isFalse();
        // The refusal is transparent: the resolved model is persisted.
        assertThat(persistedState().getResolvedWorkerModel()).isEqualTo("openai:deepseek-v4-flash-0731");
        assertThat(pool.isWorkerModelApproved(process, persistedState())).isFalse();
        // Not a single worker call was made.
        verify(lightLlmService, org.mockito.Mockito.never())
                .call(any(de.mhus.vance.brain.ai.light.LightLlmRequest.class));
    }

    @Test
    void approvedModelPassesTheGate() throws IOException {
        stubEchoWorker();
        pool.start(process);
        // The default setUp allowlist (*deepseek*) covers the resolved model.
        assertThat(persistedState().getResolvedWorkerModel()).isEqualTo("openai:deepseek-v4-flash-0731");
        assertThat(pool.isWorkerModelApproved(process, persistedState())).isTrue();
    }

    @Test
    void maxTokensOverrideFlowsIntoTheWorkerCall() throws Exception {
        stubEchoWorker();
        WowbaggerState s = persistedState();
        s.setMaxTokens(12345);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s, Map.class));
        process.setEngineParams(params);

        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        var captor = org.mockito.ArgumentCaptor.forClass(de.mhus.vance.brain.ai.light.LightLlmRequest.class);
        verify(lightLlmService, org.mockito.Mockito.atLeastOnce()).call(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(req -> assertThat(req.getMaxTokens()).isEqualTo(12345));
        pool.stop(PROC_ID);
    }

    @Test
    void tempRootDirSourceProducesAWarning() {
        // Live-run lesson: a source in a temp RootDir dies with its creator
        // process — configure must surface that BEFORE the run burns hours.
        when(workTargetService.current(process)).thenReturn(new WorkTarget(WorkTargetKind.WORK, null));
        when(workspaceService.getWorkingDir(TENANT, PROJECT, PROC_ID)).thenReturn(Optional.empty());
        // A legacy structure without workTargetName still resolves into the
        // process-temp path — the warning is the safety net for that.
        WowbaggerState legacy = persistedState();
        legacy.setWorkTargetName(null);

        assertThat(pool.sourceRootWarnings(process, legacy))
                .singleElement()
                .asString()
                .contains("process-temp RootDir");

        // A named, persistent RootDir produces no warning.
        when(workTargetService.current(process)).thenReturn(new WorkTarget(WorkTargetKind.WORK, "data"));
        assertThat(pool.sourceRootWarnings(process, persistedState())).isEmpty();
    }

    @Test
    void ensureWorkRootCreatesAdoptsAndPinsTheRunRoot() throws IOException {
        // No explicit target -> a persistent run root wowbagger-<id prefix> is
        // created, stored in the structure AND pinned as the process-wide
        // WORK target (the blank-targetName case is the tmp-RootDir trap).
        when(workTargetService.current(process)).thenReturn(new WorkTarget(WorkTargetKind.WORK, null));
        when(workspaceService.getRootDir(eq(TENANT), eq(PROJECT), anyString())).thenReturn(Optional.empty());

        WowbaggerState s = persistedState();
        String runRoot = pool.ensureWorkRoot(process, s);

        assertThat(runRoot).isEqualTo("wowbagger-" + PROC_ID.substring(0, 8));
        // the structure field is set by the call; the caller persists it
        assertThat(s.getWorkTargetName()).isEqualTo(runRoot);

        // start() pins the run root as the process-wide target and resolves
        // the source through it.
        stubEchoWorker();
        pool.start(process);
        assertThat(process.getEngineParams().get(de.mhus.vance.shared.worktarget.WorkTarget.KEY))
                .isEqualTo(new de.mhus.vance.shared.worktarget.WorkTarget(
                                de.mhus.vance.shared.worktarget.WorkTargetKind.WORK, runRoot)
                        .toMap());
        assertThat(persistedState().getWorkTargetName()).isEqualTo(runRoot);
    }

    @Test
    void clientTargetStaysUntouchedButTheRunRootIsUsedForTheSource() throws IOException {
        // Foot exception: the process-wide target is CLIENT — it must NOT be
        // re-pinned, but the pool still reads the source from the run root.
        when(workTargetService.current(process)).thenReturn(new WorkTarget(WorkTargetKind.CLIENT, null));
        when(workspaceService.getRootDir(eq(TENANT), eq(PROJECT), anyString())).thenReturn(Optional.empty());

        stubEchoWorker();
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        assertThat(process.getEngineParams().get(de.mhus.vance.shared.worktarget.WorkTarget.KEY))
                .isNull(); // untouched — the agent keeps direct client access
        assertThat(persistedState().getWorkTargetName()).isEqualTo("wowbagger-" + PROC_ID.substring(0, 8));
        // The source window was read through the run root (rotation completed).
        assertThat(persistedState().getRecordsDone()).isEqualTo(5);
    }

    @Test
    void heartbeatFiresDuringLongSilentStretches() throws IOException {
        // 5 single-record chunks on ONE slow thread (350ms per worker call)
        // keep the run grinding for ~1.75s; with wakeEverySeconds=1 and
        // progress wakeups off, the runner's 1s tick must fire a heartbeat
        // while chunk 3 is still in flight (the drained check would end the run).
        AtomicInteger slow = new AtomicInteger();
        when(lightLlmService.call(any(LightLlmRequest.class))).thenAnswer(inv -> {
            Thread.sleep(350);
            LightLlmRequest req = inv.getArgument(0);
            int count = (int) req.getUserPrompt()
                    .substring(req.getUserPrompt().indexOf("in order)\n") + "in order)\n".length())
                    .lines()
                    .count();
            StringBuilder reply = new StringBuilder();
            for (int i = 0; i < Math.max(count, 1); i++) {
                if (i > 0) {
                    reply.append('\n');
                }
                reply.append("out-").append(slow.incrementAndGet());
            }
            return reply.toString();
        });

        WowbaggerState s = persistedState();
        s.setChunkSize(1);
        s.setThreadsDesired(1);
        s.setWakeEverySeconds(1);
        s.setWakeEveryRecords(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s, Map.class));
        process.setEngineParams(params);

        pool.start(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        assertThat(pendingNotes("heartbeat")).isNotEmpty();
    }

    @Test
    void zeroThreadsParksThePoolWithoutTouchingThePointer() throws IOException {
        stubEchoWorker();
        pool.start(process);
        pool.stop(PROC_ID);

        assertThat(pool.isRunning(PROC_ID)).isFalse();
        // The structure (pointer, results) survives the park untouched.
        WowbaggerState s = persistedState();
        assertThat(s.getThreadsDesired()).isEqualTo(2);
        verify(documentService, org.mockito.Mockito.never()).update(anyString(), any(), any(), any(), any(), any());
    }

    /** All wakeup notes sent so far (pending messages) containing the needle. */
    private List<String> pendingNotes(String needle) {
        List<String> out = new java.util.ArrayList<>();
        for (var inv : org.mockito.Mockito.mockingDetails(thinkProcessService).getInvocations()) {
            if (!"appendPending".equals(inv.getMethod().getName())) {
                continue;
            }
            var msg = inv.getArgument(1, de.mhus.vance.shared.thinkprocess.PendingMessageDocument.class);
            if (msg != null && msg.getContent() != null && msg.getContent().contains(needle)) {
                out.add(msg.getContent());
            }
        }
        return out;
    }

    /** Polls the condition until true or the timeout — async pool code under test. */
    private static void waitFor(long timeoutMs, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }
}
