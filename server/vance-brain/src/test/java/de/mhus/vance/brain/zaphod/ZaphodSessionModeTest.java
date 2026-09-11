package de.mhus.vance.brain.zaphod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.api.zaphod.HeadStatus;
import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodMode;
import de.mhus.vance.api.zaphod.ZaphodPattern;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.api.zaphod.ZaphodStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.context.LanguageContextResolver;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Session-mode lifecycle of {@link ZaphodEngine} — the reactive
 * council chat (planning/zaphod-session-mode.md): greet-and-wait at
 * start, per-turn inbox fold at the turn boundary, long-lived heads
 * (failed ones respawn next turn), TodoList turn progress, synthesis
 * as the single chat reply without draft documents, and — above all —
 * no {@code closeProcess}: a session chat must survive its turns.
 */
class ZaphodSessionModeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private RecipeResolver recipeResolver;
    private LlmCallTracker llmCallTracker;
    private EnginePromptResolver enginePromptResolver;
    private SystemPromptComposer composer;
    private EngineChatFactory engineChatFactory;
    private ProcessEventEmitter eventEmitter;
    private LaneScheduler laneScheduler;
    private DocumentService documentService;
    private ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private LightLlmService lightLlmService;
    private LanguageContextResolver languageContextResolver;
    private PlanModeEventEmitter planModeEventEmitter;
    private ZaphodEngine engine;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        recipeResolver = mock(RecipeResolver.class);
        llmCallTracker = mock(LlmCallTracker.class);
        enginePromptResolver = mock(EnginePromptResolver.class);
        composer = mock(SystemPromptComposer.class);
        engineChatFactory = mock(EngineChatFactory.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        laneScheduler = mock(LaneScheduler.class);
        documentService = mock(DocumentService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThinkEngineService> provider = mock(ObjectProvider.class);
        thinkEngineServiceProvider = provider;
        lightLlmService = mock(LightLlmService.class);
        languageContextResolver = mock(LanguageContextResolver.class);
        planModeEventEmitter = mock(PlanModeEventEmitter.class);
        engine = new ZaphodEngine(
                thinkProcessService,
                chatMessageService,
                recipeResolver,
                llmCallTracker,
                enginePromptResolver,
                composer,
                engineChatFactory,
                eventEmitter,
                laneScheduler,
                objectMapper,
                documentService,
                thinkEngineServiceProvider,
                lightLlmService,
                languageContextResolver,
                planModeEventEmitter);
        ctx = mock(ThinkEngineContext.class);
        when(languageContextResolver.formatBlock(any())).thenReturn("");
    }

    // ──────────────────── fixtures ────────────────────

    private ThinkProcessDocument sessionProcess(Map<String, Object> extraParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(ZaphodEngine.SESSION_MODE_KEY, true);
        params.put(ZaphodEngine.PATTERN_KEY, "COUNCIL");
        params.put(
                ZaphodEngine.HEADS_KEY,
                List.of(
                        Map.of("name", "optimist", "recipe", "council-member"),
                        Map.of("name", "skeptiker", "recipe", "council-member")));
        if (extraParams != null) {
            params.putAll(extraParams);
        }
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("t")
                .projectId("proj")
                .sessionId("s1")
                .name("chat")
                .thinkEngine(ZaphodEngine.NAME)
                .status(ThinkProcessStatus.IDLE)
                .engineParams(params)
                .build();
    }

    /** Serialises a ready-built state into the process' engineParams —
     *  the same shape {@code persistState} produces. */
    private void seedState(ThinkProcessDocument process, ZaphodState state) {
        Map<String, Object> params = process.getEngineParams();
        params.put(ZaphodEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        process.setEngineParams(params);
    }

    private ZaphodState lastPersistedState(ThinkProcessDocument process) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService, Mockito.atLeastOnce()).replaceEngineParams(eq(process.getId()), captor.capture());
        Map<String, Object> last = captor.getValue();
        return objectMapper.convertValue(last.get(ZaphodEngine.STATE_KEY), ZaphodState.class);
    }

    private List<TodoItem> capturedTodos(int wantedInvocations) {
        ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService, Mockito.times(wantedInvocations)).setTodos(eq("p1"), captor.capture());
        return captor.getValue();
    }

    private SteerMessage.UserChatInput userInput(String fromUser, String content) {
        return new SteerMessage.UserChatInput(Instant.now(), /*idempotencyKey*/ null, fromUser, content);
    }

    // ──────────────────── start ────────────────────

    @Test
    void start_sessionMode_greetsOnceAndWaitsForInput() {
        ThinkProcessDocument process = sessionProcess(null);

        engine.start(process, ctx);

        // Greeting as ASSISTANT chat message, carrying the head names.
        ArgumentCaptor<ChatMessageDocument> msgCaptor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(msgCaptor.capture());
        ChatMessageDocument greeting = msgCaptor.getValue();
        assertThat(greeting.getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(greeting.getContent()).contains("optimist").contains("skeptiker");
        // Waiting, not running — no scheduled turn until user input wakes the lane.
        verify(eventEmitter, never()).scheduleTurn(process.getId());
        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        assertThat(lastPersistedState(process).getMode()).isEqualTo(ZaphodMode.SESSION);
    }

    @Test
    void start_sessionModeWithDebatePattern_isRejected() {
        ThinkProcessDocument process = sessionProcess(Map.of(ZaphodEngine.PATTERN_KEY, "DEBATE"));

        assertThatThrownBy(() -> engine.start(process, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sessionMode requires pattern COUNCIL");
    }

    // ──────────────────── turn boundary ────────────────────

    @Test
    void runTurn_boundary_foldsUserInputIntoTurnGoalAndStartsTurn() {
        ThinkProcessDocument process = sessionProcess(null);
        engine.start(process, ctx);
        when(ctx.drainPending()).thenReturn(List.of(userInput("alice", "Should we use A or B?")));

        engine.runTurn(process, ctx);

        ZaphodState state = lastPersistedState(process);
        assertThat(state.getTurnIndex()).isEqualTo(1);
        assertThat(state.getTurnGoal()).isEqualTo("Should we use A or B?");
        assertThat(state.getStatus()).isEqualTo(ZaphodStatus.RUNNING);
        assertThat(state.getHeads()).hasSize(2).allSatisfy(h -> {
            assertThat(h.getStatus()).isEqualTo(HeadStatus.PENDING);
            assertThat(h.getReplies()).isEmpty();
        });
        // N+1 todo items, all PENDING, stable ids.
        List<TodoItem> todos = capturedTodos(1);
        assertThat(todos).extracting(TodoItem::getStatus).containsOnly(TodoStatus.PENDING);
        assertThat(todos)
                .extracting(TodoItem::getId)
                .containsExactly("zaphod-head-optimist", "zaphod-head-skeptiker", "zaphod-conclusion");
        verify(planModeEventEmitter).emitTodosUpdated(eq(process), eq(todos));
        verify(eventEmitter).scheduleTurn(process.getId());
        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        verify(thinkProcessService, never()).closeProcess(anyString(), any());
    }

    @Test
    void runTurn_boundary_noUserInput_staysIdleQuietly() {
        ThinkProcessDocument process = sessionProcess(null);
        engine.start(process, ctx);
        when(ctx.drainPending()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        // IDLE twice: once from start(), once from the quiet boundary.
        verify(thinkProcessService, Mockito.times(2)).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        verify(eventEmitter, never()).scheduleTurn(process.getId());
        verify(thinkProcessService, never()).setTodos(anyString(), any());
    }

    @Test
    void runTurn_boundary_multipleMessagesFromMultipleSenders_foldWithSenderPrefix() {
        ThinkProcessDocument process = sessionProcess(null);
        engine.start(process, ctx);
        when(ctx.drainPending())
                .thenReturn(List.of(userInput("alice", "I prefer A."), userInput("bob", "A is too expensive.")));

        engine.runTurn(process, ctx);

        String goal = lastPersistedState(process).getTurnGoal();
        assertThat(goal).isEqualTo("[alice]\nI prefer A.\n\n[bob]\nA is too expensive.");
    }

    @Test
    void runTurn_boundary_failedHeadFromLastTurn_respawnsFresh() {
        ThinkProcessDocument process = sessionProcess(null);
        ZaphodState seeded = ZaphodState.builder()
                .mode(ZaphodMode.SESSION)
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .turnIndex(1)
                .turnGoal("old question")
                .status(ZaphodStatus.FAILED)
                .heads(List.of(
                        ZaphodHead.builder()
                                .name("optimist")
                                .recipe("council-member")
                                .status(HeadStatus.FAILED)
                                .failureReason("worker produced no assistant reply in round 0")
                                .spawnedProcessId("child-1")
                                .replies(new ArrayList<>())
                                .build(),
                        ZaphodHead.builder()
                                .name("skeptiker")
                                .recipe("council-member")
                                .status(HeadStatus.DONE)
                                .spawnedProcessId("child-2")
                                .replies(new ArrayList<>(List.of("old reply")))
                                .build()))
                .build();
        seedState(process, seeded);
        when(ctx.drainPending()).thenReturn(List.of(userInput("alice", "Next question")));
        // Healthy child still exists; failed child is gone → respawn.
        when(thinkProcessService.findById("child-2"))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id("child-2")
                        .tenantId("t")
                        .sessionId("s1")
                        .name("zaphod-p1-skeptiker")
                        .thinkEngine("ford")
                        .status(ThinkProcessStatus.IDLE)
                        .build()));

        engine.runTurn(process, ctx);

        ZaphodState state = lastPersistedState(process);
        assertThat(state.getTurnIndex()).isEqualTo(2);
        assertThat(state.getHeads().get(0).getStatus()).isEqualTo(HeadStatus.PENDING);
        assertThat(state.getHeads().get(0).getSpawnedProcessId()).isNull();
        assertThat(state.getHeads().get(0).getFailureReason()).isNull();
        // Healthy head keeps its child — persona continuity.
        assertThat(state.getHeads().get(1).getSpawnedProcessId()).isEqualTo("child-2");
        assertThat(state.getHeads().get(1).getReplies()).isEmpty();
    }

    @Test
    void runTurn_midTurn_freshHeadSpawn_isMarkedSilentMachinery() {
        // Session-mode heads are machinery: their transcripts must not
        // narrate into the session chat (silent flag → scrollback +
        // live-push filters). BATCH heads keep their visible transcript.
        ThinkProcessDocument process = sessionProcess(null);
        ZaphodState state = runningStateWithPendingHeads();
        state.getHeads().get(0).setSpawnedProcessId(null);
        seedState(process, state);
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        ThinkProcessDocument child = ThinkProcessDocument.builder()
                .id("child-1")
                .tenantId("t")
                .sessionId("s1")
                .name("zaphod-p1-optimist")
                .thinkEngine("ford")
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(thinkProcessService.create(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(child);
        de.mhus.vance.brain.recipe.AppliedRecipe applied = new de.mhus.vance.brain.recipe.AppliedRecipe(
                "council-member",
                "ford",
                Map.of(),
                /*promptOverride*/ null, /*promptOverrideAppend*/
                null,
                /*promptMode*/ null, /*dataRelayCorrection*/
                null,
                /*effectiveAllowedTools*/ Set.of("respond"),
                /*connectionProfile*/ null, /*defaultActiveSkills*/
                List.of(),
                /*allowedSkills*/ null,
                de.mhus.vance.brain.recipe.RecipeSource.VANCE,
                /*overriddenParamKeys*/ List.of(),
                /*sessionLifecycleConfig*/ null);
        when(recipeResolver.apply(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(applied);
        ThinkEngineService engineService = mock(ThinkEngineService.class);
        ThinkEngine fordEngine = mock(ThinkEngine.class);
        when(fordEngine.name()).thenReturn("ford");
        when(fordEngine.version()).thenReturn("1");
        when(engineService.resolve("ford")).thenReturn(Optional.of(fordEngine));
        when(thinkEngineServiceProvider.getObject()).thenReturn(engineService);
        when(laneScheduler.submit(eq("child-1"), any(Runnable.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(chatMessageService.history("t", "s1", "child-1"))
                .thenReturn(List.of(ChatMessageDocument.builder()
                        .tenantId("t")
                        .sessionId("s1")
                        .thinkProcessId("child-1")
                        .role(ChatRole.ASSISTANT)
                        .content("I like option A!")
                        .build()));

        engine.runTurn(process, ctx);

        // The core of fix #1: the head was spawned and marked silent.
        verify(thinkProcessService).setSilent("child-1", true);
        ZaphodState persisted = lastPersistedState(process);
        assertThat(persisted.getHeads().get(0).getSpawnedProcessId()).isEqualTo("child-1");
        assertThat(persisted.getHeads().get(0).getReplies()).containsExactly("I like option A!");
    }

    // ──────────────────── mid-turn head drive ────────────────────

    @Test
    void runTurn_midTurn_drivesHeadWithoutDrainingAndWritesNoDraft() {
        ThinkProcessDocument process = sessionProcess(null);
        seedState(process, runningStateWithPendingHeads());
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        ThinkProcessDocument child = ThinkProcessDocument.builder()
                .id("child-1")
                .tenantId("t")
                .sessionId("s1")
                .name("zaphod-p1-optimist")
                .thinkEngine("ford")
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(thinkProcessService.findById("child-1")).thenReturn(Optional.of(child));
        when(laneScheduler.submit(eq("child-1"), any(Runnable.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        ThinkEngineService engineService = mock(ThinkEngineService.class);
        when(thinkEngineServiceProvider.getObject()).thenReturn(engineService);
        when(chatMessageService.history("t", "s1", "child-1"))
                .thenReturn(List.of(ChatMessageDocument.builder()
                        .tenantId("t")
                        .sessionId("s1")
                        .thinkProcessId("child-1")
                        .role(ChatRole.ASSISTANT)
                        .content("I like option A!")
                        .build()));

        engine.runTurn(process, ctx);

        // Inbox discipline: a mid-turn runTurn must NOT drain —
        // messages arriving while heads are driven stay queued.
        verify(ctx, never()).drainPending();
        ZaphodState state = lastPersistedState(process);
        assertThat(state.getCurrentHeadIndex()).isEqualTo(1);
        ZaphodHead optimist = state.getHeads().get(0);
        assertThat(optimist.getStatus()).isEqualTo(HeadStatus.DONE);
        assertThat(optimist.getReplies()).containsExactly("I like option A!");
        // SESSION writes no draft documents.
        verify(documentService, never())
                .createText(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), any());
        // Todos ticked: IN_PROGRESS before the drive, COMPLETED after.
        List<TodoItem> todos = capturedTodos(2);
        assertThat(todos)
                .extracting(TodoItem::getId)
                .containsExactly("zaphod-head-optimist", "zaphod-head-skeptiker", "zaphod-conclusion");
        assertThat(todos.get(0).getStatus()).isEqualTo(TodoStatus.COMPLETED);
        verify(eventEmitter).scheduleTurn(process.getId());
        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        verify(thinkProcessService, never()).closeProcess(anyString(), any());
    }

    // ──────────────────── synthesis + turn end ────────────────────

    @Test
    void runTurn_synthesis_emitsReplyWithoutFooterAndReArmsWithoutClosing() {
        ThinkProcessDocument process = sessionProcess(null);
        seedState(process, synthesizableState());
        mockSynthesisChat(
                "{\"title\":\"Use A\",\"summary\":\"A wins\",\"synthesisMarkdown\":\"**A** is the pragmatic choice.\"}");
        when(thinkProcessService.pendingSize("p1")).thenReturn(0);

        engine.runTurn(process, ctx);

        ZaphodState state = lastPersistedState(process);
        assertThat(state.getStatus()).isEqualTo(ZaphodStatus.DONE);
        assertThat(state.getSynthesis()).isEqualTo("**A** is the pragmatic choice.");
        // Single chat reply: title + markdown, no draft-path footer.
        ArgumentCaptor<ChatMessageDocument> msgCaptor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(msgCaptor.getValue().getContent()).contains("Use A").contains("**A** is the pragmatic choice.");
        assertThat(msgCaptor.getValue().getContent()).doesNotContain("saved under");
        // No draft document, no closeProcess — the session chat survives.
        verify(documentService, never())
                .createText(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), any());
        verify(documentService, never()).update(anyString(), any(), any(), any(), any(), any());
        verify(thinkProcessService, never()).closeProcess(anyString(), any());
        // Todos closed with the empty list.
        verify(planModeEventEmitter).emitTodosUpdated(eq(process), eq(List.of()));
        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void runTurn_synthesis_pendingInputSchedulesNextTurn() {
        ThinkProcessDocument process = sessionProcess(null);
        seedState(process, synthesizableState());
        mockSynthesisChat("{\"title\":\"T\",\"summary\":\"S\",\"synthesisMarkdown\":\"M\"}");
        when(thinkProcessService.pendingSize("p1")).thenReturn(2);

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        verify(eventEmitter).scheduleTurn(process.getId());
        verify(thinkProcessService, never()).closeProcess(anyString(), any());
    }

    @Test
    void runTurn_synthesisFromTurnTwo_carriesPreviousConclusionBlock() {
        ThinkProcessDocument process = sessionProcess(null);
        ZaphodState state = synthesizableState();
        state.setTurnIndex(2);
        state.setSynthesisTitle("Use A");
        state.setSynthesisSummary("A wins");
        seedState(process, state);
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        mockSynthesisChat("{\"title\":\"T2\",\"summary\":\"S2\",\"synthesisMarkdown\":\"M2\"}", requestCaptor);
        when(thinkProcessService.pendingSize("p1")).thenReturn(0);

        engine.runTurn(process, ctx);

        dev.langchain4j.data.message.UserMessage userMessage = (dev.langchain4j.data.message.UserMessage)
                requestCaptor.getValue().messages().get(1);
        assertThat(userMessage.singleText()).contains("[Previous council conclusion (turn 1)]");
        assertThat(userMessage.singleText()).contains("Title: Use A");
        assertThat(userMessage.singleText()).contains("Summary: A wins");
        assertThat(userMessage.singleText()).contains("Question: And what about B?");
    }

    // ──────────────────── terminal / batch regression ────────────────────

    @Test
    void runTurn_sessionDoneOrFailed_neverClosesJustReArms() {
        ThinkProcessDocument process = sessionProcess(null);
        ZaphodState done = synthesizableState();
        done.setStatus(ZaphodStatus.DONE);
        seedState(process, done);
        when(ctx.drainPending()).thenReturn(List.of());
        when(thinkProcessService.pendingSize("p1")).thenReturn(0);

        engine.runTurn(process, ctx);

        verify(thinkProcessService, never()).closeProcess(anyString(), any());
        verify(thinkProcessService).updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Test
    void runTurn_batchDone_closesWithDoneReason_regression() {
        ThinkProcessDocument process = sessionProcess(Map.of(ZaphodEngine.SESSION_MODE_KEY, false));
        ZaphodState done = synthesizableState();
        done.setMode(ZaphodMode.BATCH);
        done.setStatus(ZaphodStatus.DONE);
        seedState(process, done);

        engine.runTurn(process, ctx);

        verify(thinkProcessService).closeProcess(process.getId(), CloseReason.DONE);
        verify(thinkProcessService, never()).setTodos(anyString(), any());
    }

    @Test
    void runTurn_legacyStateWithoutModeField_runsAsBatch() {
        // A state persisted before the session mode existed has no
        // mode/turnIndex keys — loadState must normalise to BATCH, not
        // crash (Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES) and not
        // silently mutate into a session chat.
        ThinkProcessDocument process = sessionProcess(null);
        ZaphodState done = synthesizableState();
        done.setStatus(ZaphodStatus.DONE);
        Map<String, Object> raw = objectMapper.convertValue(done, Map.class);
        raw.remove("mode");
        raw.remove("turnIndex");
        raw.remove("turnGoal");
        process.getEngineParams().put(ZaphodEngine.STATE_KEY, raw);

        engine.runTurn(process, ctx);

        verify(thinkProcessService).closeProcess(process.getId(), CloseReason.DONE);
    }

    // ──────────────────── helpers ────────────────────

    private ZaphodState runningStateWithPendingHeads() {
        return ZaphodState.builder()
                .mode(ZaphodMode.SESSION)
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .turnIndex(1)
                .turnGoal("Should we use A or B?")
                .currentHeadIndex(0)
                .status(ZaphodStatus.RUNNING)
                .heads(List.of(
                        ZaphodHead.builder()
                                .name("optimist")
                                .recipe("council-member")
                                .status(HeadStatus.PENDING)
                                .spawnedProcessId("child-1")
                                .replies(new ArrayList<>())
                                .build(),
                        ZaphodHead.builder()
                                .name("skeptiker")
                                .recipe("council-member")
                                .status(HeadStatus.PENDING)
                                .spawnedProcessId("child-2")
                                .replies(new ArrayList<>())
                                .build()))
                .build();
    }

    private ZaphodState synthesizableState() {
        return ZaphodState.builder()
                .mode(ZaphodMode.SESSION)
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .turnIndex(1)
                .turnGoal("And what about B?")
                .currentHeadIndex(2)
                .status(ZaphodStatus.RUNNING)
                .heads(List.of(
                        ZaphodHead.builder()
                                .name("optimist")
                                .recipe("council-member")
                                .status(HeadStatus.DONE)
                                .spawnedProcessId("child-1")
                                .replies(new ArrayList<>(List.of("A is great.")))
                                .build(),
                        ZaphodHead.builder()
                                .name("skeptiker")
                                .recipe("council-member")
                                .status(HeadStatus.DONE)
                                .spawnedProcessId("child-2")
                                .replies(new ArrayList<>(List.of("A is risky.")))
                                .build()))
                .build();
    }

    private void mockSynthesisChat(String json) {
        mockSynthesisChat(json, null);
    }

    private void mockSynthesisChat(String json, ArgumentCaptor<ChatRequest> requestCaptor) {
        AiChat aiChat = mock(AiChat.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(aiChat.chatModel()).thenReturn(chatModel);
        ChatResponse response =
                ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
        if (requestCaptor != null) {
            when(chatModel.chat(requestCaptor.capture())).thenReturn(response);
        } else {
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        }
        ChatBehavior behavior = ChatBehavior.single(new AiChatConfig("openai", "test-model", "test-key"));
        when(engineChatFactory.forProcess(any(), any(), eq(ZaphodEngine.NAME)))
                .thenReturn(new EngineChatFactory.EngineChatBundle(aiChat, behavior));
        when(enginePromptResolver.resolve(any(), anyString(), anyString())).thenReturn("sys");
        when(composer.render(anyString(), any())).thenReturn("rendered system");
    }
}
