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
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private ThinkProcessService thinkProcessService;
    private ToolDispatcher toolDispatcher;
    private LightLlmService lightLlmService;
    private DocumentService documentService;
    private ThinkProcessDocument process;
    private WowbaggerPoolService pool;
    private de.mhus.vance.shared.settings.SettingService settingService;
    private de.mhus.vance.brain.recipe.RecipeResolver recipeResolver;
    private final Map<String, DocumentDocument> docsByPath = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callCounter = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> docContents = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        thinkProcessService = mock(ThinkProcessService.class);
        lightLlmService = mock(LightLlmService.class);
        documentService = mock(DocumentService.class);
        toolDispatcher = mock(ToolDispatcher.class);
        ChatMessageService chatLog = mock(ChatMessageService.class);
        MetricService metricService = new MetricService(new SimpleMeterRegistry());
        ObjectMapper om = JsonMapper.builder().build();
        recipeResolver = mock(de.mhus.vance.brain.recipe.RecipeResolver.class);
        de.mhus.vance.brain.ai.AiModelResolver aiModelResolver = mock(de.mhus.vance.brain.ai.AiModelResolver.class);
        settingService = mock(de.mhus.vance.shared.settings.SettingService.class);

        de.mhus.vance.brain.wowbagger.worker.WowbaggerWorkerRegistry workers =
                new de.mhus.vance.brain.wowbagger.worker.WowbaggerWorkerRegistry(
                        List.of(new de.mhus.vance.brain.wowbagger.worker.LightLlmWorker(
                                lightLlmService, aiModelResolver)),
                        recipeResolver);
        pool = new WowbaggerPoolService(
                thinkProcessService,
                workers,
                documentService,
                toolDispatcher,
                metricService,
                om,
                chatLog,
                settingService);

        // Worker-model resolution: recipe → params.model spec → resolved model.
        de.mhus.vance.brain.recipe.ResolvedRecipe workerRecipe = mock(de.mhus.vance.brain.recipe.ResolvedRecipe.class);
        lenient().when(workerRecipe.params()).thenReturn(Map.of("model", "spec-fast"));
        lenient().when(workerRecipe.name()).thenReturn("wowbagger-worker");
        lenient().when(workerRecipe.engine()).thenReturn("jeltz");
        lenient().when(workerRecipe.internal()).thenReturn(true);
        lenient()
                .when(recipeResolver.resolve(eq(TENANT), eq(PROJECT), anyString()))
                .thenReturn(Optional.of(workerRecipe));
        lenient()
                .when(aiModelResolver.resolveOrDefault(eq("default:spec-fast"), eq(TENANT), eq(PROJECT), eq(PROC_ID)))
                .thenReturn(new de.mhus.vance.brain.ai.AiModelResolver.Resolved(
                        "openai", "cortecs", "deepseek-v4-flash-0731", false));
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

        writeSource(List.of("r1", "r2", "r3", "r4", "r5"));

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
                    if (docsByPath.containsKey(path)) {
                        // Mirror the real service: the path is taken (force
                        // overwrites via update, resumes adopt).
                        throw new DocumentService.DocumentAlreadyExistsException("Document already exists: " + path);
                    }
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("doc-" + path);
                    doc.setPath(path);
                    docsByPath.put(path, doc);
                    docContents.put(doc.getId(), inv.getArgument(5, String.class));
                    return doc;
                });
        lenient()
                .when(documentService.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new java.io.ByteArrayInputStream(docContents
                        .get(inv.getArgument(0, DocumentDocument.class).getId())
                        .getBytes(StandardCharsets.UTF_8)));
        lenient()
                .when(documentService.readContent(any()))
                .thenAnswer(inv -> docContents.get(
                        inv.getArgument(0, DocumentDocument.class).getId()));
    }

    /**
     * Registers the run's imported source document, as {@code wowbagger_start}
     * would have left it — under both format suffixes, so a test may switch the
     * input format without re-registering.
     */
    private void writeSource(List<String> records) {
        String content = String.join("\n", records) + "\n";
        for (String suffix : List.of("txt", "jsonl")) {
            String path = "_wowbagger/" + PROC_ID + "/source." + suffix;
            DocumentDocument doc = new DocumentDocument();
            doc.setId("doc-" + path);
            doc.setPath(path);
            docsByPath.put(path, doc);
            docContents.put(doc.getId(), content);
        }
    }

    private void removeSource() {
        for (String suffix : List.of("txt", "jsonl")) {
            docsByPath.remove("_wowbagger/" + PROC_ID + "/source." + suffix);
        }
    }

    private ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, "sess-1", PROC_ID, "user-1");
    }

    /** Echo answer matching the record count of the request — the worker contract. */
    private Object echoAnswerFor(LightLlmRequest req, String tag) {
        int marker = req.getUserPrompt().indexOf("in order)\n");
        int count = marker < 0
                ? 1
                : (int) req.getUserPrompt()
                        .substring(marker + "in order)\n".length())
                        .lines()
                        .count();
        StringBuilder reply = new StringBuilder();
        for (int i = 0; i < Math.max(count, 1); i++) {
            if (i > 0) {
                reply.append('\n');
            }
            reply.append(tag).append(i);
        }
        return new de.mhus.vance.brain.ai.light.LightLlmTextAnswer(
                reply.toString(),
                "cortecs:deepseek-v4-flash-0731",
                new de.mhus.vance.brain.ai.light.LightLlmJsonAnswer.Usage(100, 200));
    }

    private void stubEchoWorker() {
        AtomicInteger call = new AtomicInteger();
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
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
            return new de.mhus.vance.brain.ai.light.LightLlmTextAnswer(
                    reply.toString(),
                    "cortecs:deepseek-v4-flash-0731",
                    new de.mhus.vance.brain.ai.light.LightLlmJsonAnswer.Usage(100, 200));
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
    void anyWorkerTypeRunsThroughThePool_withACommitHookPerChunk_andNoTaskOrAllowlistWhenItNeedsNone()
            throws IOException {
        AtomicInteger commits = new AtomicInteger();
        de.mhus.vance.brain.wowbagger.worker.WowbaggerWorker stub =
                new de.mhus.vance.brain.wowbagger.worker.WowbaggerWorker() {
                    @Override
                    public String kind() {
                        return "stub";
                    }

                    @Override
                    public String describe() {
                        return "test stub";
                    }

                    @Override
                    public boolean supports(de.mhus.vance.brain.recipe.ResolvedRecipe recipe) {
                        return true;
                    }

                    @Override
                    public de.mhus.vance.brain.wowbagger.worker.PreparedWorker prepare(
                            de.mhus.vance.brain.wowbagger.worker.WorkerSetup setup) {
                        return new de.mhus.vance.brain.wowbagger.worker.PreparedWorker() {
                            @Override
                            public String kind() {
                                return "stub";
                            }

                            @Override
                            public String recipeName() {
                                return setup.spec().recipeName();
                            }

                            @Override
                            public @org.jspecify.annotations.Nullable String resolvedModel() {
                                return null; // gates per call — no allowlist check up front
                            }

                            @Override
                            public boolean usesTask() {
                                return false;
                            }

                            @Override
                            public de.mhus.vance.brain.wowbagger.worker.WorkerReply process(
                                    List<String> records, de.mhus.vance.brain.wowbagger.worker.ChunkContext chunk) {
                                return new de.mhus.vance.brain.wowbagger.worker.WorkerReply(
                                        records.stream().map(r -> "done-" + r).toList(), records.size(), 0, 0);
                            }

                            @Override
                            public void onCommit() {
                                commits.incrementAndGet();
                            }
                        };
                    }
                };
        WowbaggerPoolService stubPool = new WowbaggerPoolService(
                thinkProcessService,
                new de.mhus.vance.brain.wowbagger.worker.WowbaggerWorkerRegistry(List.of(stub), recipeResolver),
                documentService,
                toolDispatcher,
                new MetricService(new SimpleMeterRegistry()),
                JsonMapper.builder().build(),
                mock(ChatMessageService.class),
                settingService);
        // No allowlist at all — a worker without a fixed model is not gated up front.
        lenient()
                .when(settingService.getStringValue(
                        eq(TENANT),
                        eq(de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT),
                        anyString(),
                        eq(WowbaggerPoolService.ALLOWED_MODELS_KEY)))
                .thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw =
                (Map<String, Object>) process.getEngineParams().get(WowbaggerPoolService.ENGINE_STATE_KEY);
        raw.remove("task");
        assertThat(stubPool.taskRequired(process, persistedState())).isFalse();

        stubPool.start(process);

        verify(documentService, timeout(20_000).times(4))
                .createText(eq(TENANT), eq(PROJECT), anyString(), any(), any(), anyString(), any(), any());
        waitFor(20_000, () -> !stubPool.isRunning(PROC_ID));
        assertThat(commits.get()).as("one commit hook per published chunk").isEqualTo(3);
        WowbaggerState s = persistedState();
        assertThat(s.getWorkerKind()).isEqualTo("stub");
        assertThat(s.getResolvedWorkerModel()).isNull();
        assertThat(s.getCounters().getWorkerCalls()).isEqualTo(5);
        assertThat(s.getRecordsDone()).isEqualTo(5);
    }

    @Test
    void switchWorkerPersistsTheCheckedWorkerAndRefusesWhileRunningOrUnapproved() throws IOException {
        // check only: nothing persisted
        WowbaggerPoolService.WorkerSwitch checked = pool.switchWorker(process, "other-worker", null, true);
        assertThat(checked.applied()).isFalse();
        assertThat(checked.kind()).isEqualTo("llm");
        assertThat(persistedState().getWorkerRecipe()).isNull();

        // applied: persisted with kind/model
        WowbaggerPoolService.WorkerSwitch applied = pool.switchWorker(process, "other-worker", null, false);
        assertThat(applied.applied()).isTrue();
        assertThat(persistedState().getWorkerRecipe()).isEqualTo("other-worker");
        assertThat(persistedState().getWorkerKind()).isEqualTo("llm");
        assertThat(persistedState().getResolvedWorkerModel()).isEqualTo("cortecs:deepseek-v4-flash-0731");

        // unapproved model: refused, structure untouched
        lenient()
                .when(settingService.getStringValue(
                        eq(TENANT),
                        eq(de.mhus.vance.shared.settings.SettingService.SCOPE_PROJECT),
                        eq(PROJECT),
                        eq(WowbaggerPoolService.ALLOWED_MODELS_KEY)))
                .thenReturn("*gpt*");
        assertThatThrownBy(() -> pool.switchWorker(process, "third-worker", null, false))
                .hasMessageContaining("not approved");
        assertThat(persistedState().getWorkerRecipe()).isEqualTo("other-worker");
    }

    @Test
    void switchWorkerRefusesWhileThePoolRuns() throws IOException {
        stubEchoWorker();
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
            Thread.sleep(300); // keep the pool busy
            return echoAnswerFor(inv.getArgument(0), "x");
        });
        pool.start(process);

        assertThatThrownBy(() -> pool.switchWorker(process, "other-worker", null, false))
                .hasMessageContaining("the pool is running");
        pool.stop(PROC_ID);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
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
        // start/finish wakeups reached the agent's pending queue. finish()
        // releases the run *before* it sends the finish wakeup (an agent that
        // reacts to the note must already see the pool as stopped), so
        // "not running" does not yet mean "note sent" — wait for the note.
        waitFor(20_000, () -> !pendingNotes("run finished").isEmpty());
        verify(thinkProcessService, atLeast(2)).appendPending(eq(PROC_ID), any(), anyString());
    }

    @Test
    void exhaustedRetriesLandInTheFailureLedgerWithCooldownWakeups() throws IOException {
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class)))
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
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class)))
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
                .hasMessageContaining("cortecs:deepseek-v4-flash-0731")
                .hasMessageContaining(WowbaggerPoolService.ALLOWED_MODELS_KEY);

        assertThat(pool.isRunning(PROC_ID)).isFalse();
        // The refusal is transparent: the resolved model is persisted.
        assertThat(persistedState().getResolvedWorkerModel()).isEqualTo("cortecs:deepseek-v4-flash-0731");
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
        assertThat(persistedState().getResolvedWorkerModel()).isEqualTo("cortecs:deepseek-v4-flash-0731");
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
        verify(lightLlmService, org.mockito.Mockito.atLeastOnce()).callWithUsage(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(req -> assertThat(req.getMaxTokens()).isEqualTo(12345));
        pool.stop(PROC_ID);
    }

    @Test
    void tokenCountersAccumulateAcrossWorkerCalls() throws IOException {
        stubEchoWorker();
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        WowbaggerState s = persistedState();
        // 3 chunks x (100 in / 200 out) tokens per answered call.
        assertThat(s.getCounters().getTokensIn()).isEqualTo(300);
        assertThat(s.getCounters().getTokensOut()).isEqualTo(600);
        assertThat(s.getCounters().getWorkerCalls()).isEqualTo(3);
        pool.stop(PROC_ID);
    }

    @Test
    void forceReRunResetsAndOverwritesPublishedChunks() throws IOException {
        stubEchoWorker();
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        assertThat(persistedState().getRecordsDone()).isEqualTo(5);
        assertThat(persistedState().getCounters().getTokensIn()).isEqualTo(300);

        // Force: re-imports the source, pointer/done/counters reset, published
        // chunk docs overwritten.
        stubFileRead("r1\nr2\nr3\nr4\nr5");
        lenient()
                .when(documentService.replaceContent(anyString(), any(InputStream.class), any(), any(), any()))
                .thenReturn(null);
        pool.start(process, true, ctx());
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        WowbaggerState s = persistedState();
        assertThat(s.getRecordsDone()).isEqualTo(5);
        assertThat(s.getPointer()).isEqualTo(5);
        assertThat(s.getFailureCount()).isZero();
        // Counters were reset, then 3 fresh calls re-accumulated.
        assertThat(s.getCounters().getTokensIn()).isEqualTo(300);
        assertThat(s.getCounters().getWorkerCalls()).isEqualTo(3);
        // 3 chunk docs OVERWRITTEN on the second pass (createText threw, update
        // ran) + the merged result doc refreshed once = 4 updates total.
        verify(documentService, org.mockito.Mockito.times(4)).update(any(), any(), any(), any(), any(), any());
        pool.stop(PROC_ID);
    }

    @Test
    void jsonlInputWithBrokenRecordsFailsTheChunkWithoutWorkerCalls() throws IOException {
        // Canonical-JSONL contract: a broken conversion script must surface at
        // claim time — the chunk fails with a named record, no provider call.
        writeSource(List.of("not json at all", "also not json"));
        WowbaggerState s0 = persistedState();
        s0.setInputFormat("jsonl");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s0, Map.class));
        process.setEngineParams(params);

        stubEchoWorker(); // must never be reached
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        WowbaggerState s = persistedState();
        assertThat(s.getRecordsDone()).isZero();
        assertThat(s.getFailedChunks()).hasSize(1);
        assertThat(s.getFailedChunks().getFirst().getLastError())
                .contains("record 0 is not a JSON object")
                .contains("not json at all");
        // Deterministic input failure: not a single LLM call was made.
        verify(lightLlmService, org.mockito.Mockito.never())
                .callWithUsage(any(de.mhus.vance.brain.ai.light.LightLlmRequest.class));
        pool.stop(PROC_ID);
    }

    @Test
    void linesInputSkipsJsonValidation() throws IOException {
        // inputFormat lines (the default when unset): free-form text lines
        // pass through verbatim — no JSON contract on the input side.
        assertThat(persistedState().getInputFormat()).isEqualTo("lines");
        stubEchoWorker();
        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        assertThat(persistedState().getRecordsDone()).isEqualTo(5);
        pool.stop(PROC_ID);
    }

    @Test
    void preflightReportsReadyWithCountsAndSamples() {
        Map<String, Object> report = pool.preflight(process, persistedState(), ctx());
        assertThat(report.get("ready")).isEqualTo(true);
        assertThat(report.get("problems")).isEqualTo(List.of());
        assertThat(report.get("recordsTotal")).isEqualTo(5L);
        assertThat(report.get("invalidRecords")).isEqualTo(0L);
        assertThat(report.get("chunksTotal")).isEqualTo(3);
        assertThat((java.util.List<?>) report.get("sampleRecords")).hasSize(2);
        assertThat(report.get("workerModel")).isEqualTo("cortecs:deepseek-v4-flash-0731");
        assertThat(report.get("modelApproved")).isEqualTo(true);
        assertThat(String.valueOf(report.get("sourceImported"))).startsWith("document");
    }

    @Test
    void preflightNamesBrokenJsonlRecordsAndMissingSource() {
        // Broken conversion script: the preflight names the bad records.
        writeSource(List.of("{\"id\":1}", "broken", "also broken"));
        WowbaggerState s = persistedState();
        s.setInputFormat("jsonl");
        Map<String, Object> report = pool.preflight(process, s, ctx());
        assertThat(report.get("ready")).isEqualTo(false);
        assertThat((java.util.List<?>) report.get("problems"))
                .anyMatch(p -> p.toString().contains("2 of 3 records are not JSON objects"));
        assertThat((java.util.List<?>) report.get("invalidSamples"))
                .hasSize(2)
                .allSatisfy(sample -> assertThat(sample.toString()).contains("broken"));

        // Not imported yet and the file is unreadable: a named problem, not an exception.
        removeSource();
        when(toolDispatcher.invoke(eq("file_read"), any(), any()))
                .thenThrow(new de.mhus.vance.toolpack.ToolException("file not found: input.txt"));
        s.setInputFormat("lines");
        report = pool.preflight(process, s, ctx());
        assertThat(report.get("ready")).isEqualTo(false);
        assertThat((java.util.List<?>) report.get("problems"))
                .anyMatch(p -> p.toString().contains("source unreadable"));
    }

    @Test
    void preflightBeforeTheFirstStartStreamsTheFileFromTheWorkTarget() {
        // Nothing imported yet: the preflight reads through file_read, the same
        // bytes the import would copy — without creating the source document.
        removeSource();
        stubFileRead("r1\nr2\nr3\nr4\nr5");

        Map<String, Object> report = pool.preflight(process, persistedState(), ctx());

        assertThat(report.get("ready")).isEqualTo(true);
        assertThat(report.get("recordsTotal")).isEqualTo(5L);
        assertThat(String.valueOf(report.get("sourceImported"))).startsWith("not yet");
        verify(documentService, org.mockito.Mockito.never())
                .create(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(InputStream.class),
                        any(),
                        any());
    }

    /** {@code file_read} over a fixed text: serves {@code startLine}/{@code maxLines} windows like the real tool. */
    private void stubFileRead(String text) {
        List<String> all = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        when(toolDispatcher.invoke(eq("file_read"), any(), any())).thenAnswer(inv -> {
            Map<String, Object> params = inv.getArgument(1);
            int start = ((Number) params.get("startLine")).intValue();
            int max = ((Number) params.get("maxLines")).intValue();
            List<String> window = all.stream().skip(start - 1L).limit(max).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", String.join("\n", window));
            out.put("truncated", false);
            return out;
        });
    }

    @Test
    void firstStartImportsTheSourceFromTheWorkTargetIntoADocument() throws IOException {
        // CLOUD case: no workspace, the file lives on a runner/client. The first
        // start copies it into the run's source document and works on that.
        removeSource();
        stubFileRead("r1\nr2\nr3\nr4\nr5");
        lenient()
                .when(documentService.create(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(InputStream.class),
                        any(),
                        any()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(2, String.class);
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("doc-" + path);
                    doc.setPath(path);
                    docsByPath.put(path, doc);
                    docContents.put(
                            doc.getId(),
                            new String(inv.getArgument(6, InputStream.class).readAllBytes(), StandardCharsets.UTF_8));
                    return doc;
                });
        stubEchoWorker();

        pool.start(process, false, ctx());
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        assertThat(docContents.get("doc-_wowbagger/proc0001/source.txt")).isEqualTo("r1\nr2\nr3\nr4\nr5\n");
        assertThat(persistedState().getRecordsDone()).isEqualTo(5);
        assertThat(pendingNotes("source imported")).isNotEmpty();
        pool.stop(PROC_ID);
    }

    @Test
    void resumeReadsTheImportedDocumentAndNeverTouchesTheTarget() throws IOException {
        // Pod switch / client gone: the source document exists, so a start
        // without a caller context must run entirely off it.
        stubEchoWorker();

        pool.start(process);
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        assertThat(persistedState().getRecordsDone()).isEqualTo(5);
        org.mockito.Mockito.verifyNoInteractions(toolDispatcher);
        pool.stop(PROC_ID);
    }

    @Test
    void startWithoutImportedSourceAndWithoutContextFailsClearly() {
        removeSource();

        assertThatThrownBy(() -> pool.start(process))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not been imported");
    }

    @Test
    void forceReimportsTheSourceFromTheTarget() throws IOException {
        // force = the source may have changed: the document is overwritten.
        stubFileRead("n1\nn2\nn3");
        lenient()
                .when(documentService.replaceContent(anyString(), any(InputStream.class), any(), any(), any()))
                .thenAnswer(inv -> {
                    docContents.put(
                            inv.getArgument(0, String.class),
                            new String(inv.getArgument(1, InputStream.class).readAllBytes(), StandardCharsets.UTF_8));
                    return null;
                });
        stubEchoWorker();

        pool.start(process, true, ctx());
        waitFor(20_000, () -> !pool.isRunning(PROC_ID));

        assertThat(docContents.get("doc-_wowbagger/proc0001/source.txt")).isEqualTo("n1\nn2\nn3\n");
        assertThat(persistedState().getRecordsDone()).isEqualTo(3);
        pool.stop(PROC_ID);
    }

    @Test
    void heartbeatFiresDuringLongSilentStretches() throws IOException {
        // 5 single-record chunks on ONE slow thread (350ms per worker call)
        // keep the run grinding for ~1.75s; with wakeEverySeconds=1 and
        // progress wakeups off, the runner's 1s tick must fire a heartbeat
        // while chunk 3 is still in flight (the drained check would end the run).
        AtomicInteger slow = new AtomicInteger();
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
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
            return new de.mhus.vance.brain.ai.light.LightLlmTextAnswer(
                    reply.toString(),
                    "cortecs:deepseek-v4-flash-0731",
                    new de.mhus.vance.brain.ai.light.LightLlmJsonAnswer.Usage(100, 200));
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
    void threadReductionMidRunRetiresBusyWorkersInsteadOfKillingThem() throws Exception {
        // Regression shape of the first live run: the surplus-kill used
        // Thread.interrupt(), which poisons every subsequent MongoDB operation
        // on the interrupted thread ("Interrupted waiting for lock") — the
        // worker died mid-chunk and its claim was lost from the wave. Now a
        // reduction only sets the retire flag: the busy worker finishes its
        // current chunk, commits, and exits cleanly at the next loop top.
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            records.add("r" + i);
        }
        writeSource(records);
        WowbaggerState s = persistedState();
        s.setChunkSize(2);
        s.setThreadsDesired(2);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s, Map.class));
        process.setEngineParams(params);

        AtomicInteger call = new AtomicInteger();
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
            Thread.sleep(25); // keep chunks in flight while the reduction lands
            if (call.incrementAndGet() == 5) {
                // Reduce while at least one other worker holds a chunk in the wave.
                WowbaggerState live = pool.structure(PROC_ID);
                live.setThreadsDesired(1);
                pool.persistStructure(process, live);
            }
            return echoAnswerFor(inv.getArgument(0, LightLlmRequest.class), "out-");
        });

        pool.start(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        WowbaggerState end = persistedState();
        assertThat(end.isFinished()).as("state=%s", end).isTrue();
        assertThat(end.getRecordsDone()).isEqualTo(20);
        assertThat(end.getWave()).isEmpty();
        assertThat(end.getFailedChunks()).isEmpty();
    }

    @Test
    void orphanedWaveChunkIsRequeuedWhenItsWorkerDies() throws Exception {
        // A worker dying mid-chunk (crash outside the per-chunk failure
        // handling — an Error, not a RuntimeException) leaves its claim in
        // the wave with no worker left. The runner tick must requeue it, so
        // a respawned worker finishes the chunk and the run still completes.
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            records.add("r" + i);
        }
        writeSource(records);
        WowbaggerState s = persistedState();
        s.setChunkSize(2);
        s.setThreadsDesired(1); // one worker → the crash leaves nobody behind
        s.setChunkRetries(3);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s, Map.class));
        process.setEngineParams(params);

        AtomicInteger call = new AtomicInteger();
        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
            if (call.incrementAndGet() == 3) {
                // Escapes workerLoop's RuntimeException handling — a hard crash.
                throw new LinkageError("simulated worker death");
            }
            return echoAnswerFor(inv.getArgument(0, LightLlmRequest.class), "out-");
        });

        pool.start(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        WowbaggerState end = persistedState();
        assertThat(end.isFinished()).as("state=%s", end).isTrue();
        // Chunks 0 and 1 committed directly; the orphaned chunk 2 was requeued
        // with its attempt counted, then finished by the respawned worker.
        assertThat(end.getRecordsDone()).isEqualTo(6);
        assertThat(end.getWave()).isEmpty();
        assertThat(end.getRetryQueue()).isEmpty();
        assertThat(end.getFailedChunks()).isEmpty();
        assertThat(end.getFailureCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void orphanedWaveChunkWithExhaustedBudgetLandsInTheFailureLedger() throws Exception {
        // Same worker death, but the chunk has no retry budget left: the
        // reconcile must park it for the agent instead of requeueing forever.
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            records.add("r" + i);
        }
        writeSource(records);
        WowbaggerState s = persistedState();
        s.setChunkSize(2);
        s.setThreadsDesired(1);
        s.setChunkRetries(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(s, Map.class));
        process.setEngineParams(params);

        when(lightLlmService.callWithUsage(any(LightLlmRequest.class))).thenAnswer(inv -> {
            AtomicInteger calls = callCounter.computeIfAbsent("budget", k -> new AtomicInteger());
            if (calls.incrementAndGet() == 3) {
                throw new LinkageError("simulated worker death");
            }
            return echoAnswerFor(inv.getArgument(0, LightLlmRequest.class), "out-");
        });

        pool.start(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        WowbaggerState end = persistedState();
        assertThat(end.isFinished()).as("state=%s", end).isTrue();
        // Chunks 0 and 1 committed; the orphaned chunk 2 failed into the ledger.
        assertThat(end.getRecordsDone()).isEqualTo(4);
        assertThat(end.getFailedChunks()).hasSize(1);
        assertThat(end.getFailedChunks().get(0).getIndex()).isEqualTo(2);
        assertThat(end.getRetryQueue()).isEmpty();
        assertThat(end.getWave()).isEmpty();
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

    @Test
    void retryChunkBeyondTheEofIsParkedAsFailedNotSilentlyDropped() throws IOException {
        stubEchoWorker();
        // A parked run whose source shrank while it was away: the retry
        // chunk's window lies beyond the EOF. Its records are unreadable —
        // the chunk must land in the failure ledger for the agent, not
        // vanish as an unexplained merge gap (Code-Review 15, L1).
        WowbaggerState state = persistedState();
        state.setRecordsTotal(5);
        state.setPointer(5);
        state.setRecordsDone(5);
        state.setThreadsDesired(1);
        WowbaggerState.WaveChunk orphan = new WowbaggerState.WaveChunk();
        orphan.setIndex(9);
        orphan.setStartRecord(50);
        orphan.setRecordCount(2);
        orphan.setAttempts(1);
        state.getRetryQueue().add(orphan);
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>(process.getEngineParams());
        params.put(
                WowbaggerPoolService.ENGINE_STATE_KEY,
                JsonMapper.builder().build().convertValue(state, java.util.Map.class));
        process.setEngineParams(params);

        pool.start(process);

        waitFor(20_000, () -> !pool.isRunning(PROC_ID));
        WowbaggerState s = persistedState();
        assertThat(s.getFailedChunks()).hasSize(1);
        assertThat(s.getFailedChunks().get(0).getStartRecord()).isEqualTo(50);
        assertThat(s.getFailedChunks().get(0).getLastError()).contains("source shrank");
        assertThat(s.getFailureCount()).isEqualTo(1);
        // The readable records were already committed — the run finishes and
        // hands the unreadable chunk to the agent as a decision.
        assertThat(s.isFinished()).isTrue();
        assertThat(s.getRecordsDone()).isEqualTo(5);
        // No provider call was burned on an unreadable window.
        verify(lightLlmService, org.mockito.Mockito.never()).callWithUsage(any(LightLlmRequest.class));
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
