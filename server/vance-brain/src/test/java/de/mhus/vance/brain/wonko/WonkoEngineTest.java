package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetAnswerDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.context.LanguageContextResolver;
import de.mhus.vance.brain.discovery.DiscoveryService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.util.JsonSchemaLight;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Engine-level paths around the worker and the question sets: a worker's
 * question reaches the worker with the user's answer, a free-text reply to an
 * offered follow-up is no consent, and a worker's change waits for an open
 * question set instead of dropping it.
 */
class WonkoEngineTest {

    private static final String SUBJECT = "spec.md";
    private static final String WORKER_ID = "worker-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ThinkProcessService thinkProcessService;
    private LightLlmService lightLlmService;
    private WonkoItemHandlers handlers;
    private EngineMessageRouter messageRouter;
    private ThinkEngineContext ctx;
    private ChatMessageService chat;
    private ContextToolsApi tools;
    private WonkoEngine engine;
    private ThinkProcessDocument process;

    /** The subject's current text — a worker "changes" it by replacing this. */
    private String subjectText = "Exports go to us-west-2.";

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        lightLlmService = mock(LightLlmService.class);
        WonkoRegisterService registerService = mock(WonkoRegisterService.class);
        handlers = mock(WonkoItemHandlers.class);
        messageRouter = mock(EngineMessageRouter.class);
        LanguageContextResolver languageContextResolver = mock(LanguageContextResolver.class);
        MetricService metricService = mock(MetricService.class);
        WonkoSkills wonkoSkills = mock(WonkoSkills.class);

        WonkoRegister register = WonkoTestData.register(MandateLevel.CONFIRM);
        when(registerService.loadOrCreate(any(), any(), any(), any())).thenReturn(register);
        when(languageContextResolver.formatBlock(any())).thenReturn("");
        when(metricService.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        when(wonkoSkills.select(any(), any(), any())).thenReturn(new WonkoSkills.Selection(List.of(), List.of()));
        when(messageRouter.dispatch(any(), any(), any())).thenReturn(true);

        chat = mock(ChatMessageService.class);
        tools = mock(ContextToolsApi.class);
        when(tools.invoke(eq("doc_read"), any())).thenAnswer(inv -> Map.of("path", SUBJECT, "content", subjectText));
        ctx = mock(ThinkEngineContext.class);
        when(ctx.chatMessageService()).thenReturn(chat);
        when(ctx.tools()).thenReturn(tools);
        when(ctx.hasPending()).thenReturn(false);

        engine = new WonkoEngine(
                thinkProcessService,
                objectMapper,
                lightLlmService,
                registerService,
                handlers,
                mock(PlanModeEventEmitter.class),
                mock(ProcessEventEmitter.class),
                mock(SessionService.class),
                languageContextResolver,
                metricService,
                wonkoSkills,
                mock(DiscoveryService.class),
                messageRouter);
    }

    // ──────────────────── fixtures ────────────────────

    /** A chat review on {@link #SUBJECT} with the given run state. */
    private void processWith(WonkoState state) {
        state.setSubjectRefs(List.of(SUBJECT));
        state.setSubjectKey(WonkoRegisterService.subjectKey(List.of(SUBJECT)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(WonkoEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        process = ThinkProcessDocument.builder()
                .id("wonko-1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("sess-1")
                .name("wonko")
                .engineParams(params)
                .build();
    }

    /** A finished review with a worker that is still alive. */
    private static WonkoState finishedWithWorker() {
        WonkoState state = new WonkoState();
        state.setFinished(true);
        state.setFinalReport("# Review\n- Region: EU");
        state.setWorkerName("wonko-work-abc");
        state.setWorkerId(WORKER_ID);
        return state;
    }

    private WonkoState state() {
        return objectMapper.convertValue(process.getEngineParams().get(WonkoEngine.STATE_KEY), WonkoState.class);
    }

    /** One lane turn per drain, in order. */
    private void turns(List<List<SteerMessage>> drains) {
        java.util.Iterator<List<SteerMessage>> next = drains.iterator();
        when(ctx.drainPending()).thenAnswer(inv -> next.next());
        for (int i = 0; i < drains.size(); i++) {
            engine.runTurn(process, ctx);
        }
    }

    private static List<SteerMessage> msgs(SteerMessage... messages) {
        return List.of(messages);
    }

    private static SteerMessage.Reply workerReply(String text) {
        return new SteerMessage.Reply(Instant.now(), null, WORKER_ID, "wonko-work-abc", text, null, null);
    }

    private static SteerMessage.UserChatInput userSays(String text) {
        return new SteerMessage.UserChatInput(Instant.now(), null, "alice", text);
    }

    private static SteerMessage.UserChatInput userAnswers(String setId, String text, QuestionAnswerDto... answers) {
        return new SteerMessage.UserChatInput(
                Instant.now(),
                null,
                "alice",
                null,
                text,
                List.of(),
                false,
                null,
                null,
                null,
                null,
                QuestionSetAnswerDto.builder()
                        .setId(setId)
                        .answers(List.of(answers))
                        .build());
    }

    private void routeAnswers(Map<String, Object> verdict) {
        when(lightLlmService.callForJson(any())).thenReturn(verdict);
    }

    private LightLlmRequest routeRequest() {
        ArgumentCaptor<LightLlmRequest> captor = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlmService).callForJson(captor.capture());
        return captor.getValue();
    }

    private @Nullable String forwardedToWorker() {
        ArgumentCaptor<PendingMessageDocument> captor = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(messageRouter).dispatch(eq("wonko-1"), eq(WORKER_ID), captor.capture());
        return captor.getValue().getContent();
    }

    private List<String> assistantMessages() {
        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chat, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        return captor.getAllValues().stream()
                .filter(m -> m.getRole() == ChatRole.ASSISTANT)
                .map(ChatMessageDocument::getContent)
                .toList();
    }

    // ──────────────────── finding: a worker's question reaches the worker ────────────────────

    @Test
    void workerReply_marksTheWorkerWaiting_andGoesToTheChat() {
        processWith(finishedWithWorker());

        turns(List.of(msgs(workerReply("Which one: A or B?"))));

        WonkoState after = state();
        assertThat(after.isWorkerWaiting()).isTrue();
        assertThat(after.getWorkerLastReply()).isEqualTo("Which one: A or B?");
        assertThat(assistantMessages()).contains("Which one: A or B?");
    }

    @Test
    void answerToTheWaitingWorker_isForwardedUnchanged_notAsANewTask() {
        processWith(finishedWithWorker());
        routeAnswers(Map.of("intent", "worker", "reason", "answers the worker's question"));

        turns(List.of(msgs(workerReply("Which one: A or B?")), msgs(userSays("A"))));

        assertThat(forwardedToWorker()).isEqualTo("A");
        LightLlmRequest request = routeRequest();
        assertThat(request.getUserPrompt()).contains("A worker waits for the user", "Which one: A or B?");
        assertThat(JsonSchemaLight.validate(Map.of("intent", "worker", "reason", "x"), request.getSchema())
                        .valid())
                .isTrue();
        verify(tools, never()).invokeInternal(any(), any());
        WonkoState after = state();
        assertThat(after.isWorkerWaiting()).isFalse();
        assertThat(after.getUserStatements()).isEmpty();
        assertThat(after.isFinished()).as("the review is not restarted").isTrue();
    }

    @Test
    void routeFailure_whileTheWorkerWaits_handsTheMessageToTheWorker() {
        processWith(finishedWithWorker());
        when(lightLlmService.callForJson(any())).thenThrow(new IllegalStateException("model down"));

        turns(List.of(msgs(workerReply("Which one: A or B?")), msgs(userSays("B"))));

        assertThat(forwardedToWorker()).isEqualTo("B");
        assertThat(state().getUserStatements()).isEmpty();
    }

    @Test
    void messageForTheReview_whileTheWorkerWaits_staysWithWonko() {
        processWith(finishedWithWorker());
        routeAnswers(Map.of("intent", "question", "reason", "asks about the review", "answer", "One gap is open."));

        turns(List.of(msgs(workerReply("Done — changed the region.")), msgs(userSays("what is still open?"))));

        verify(messageRouter, never()).dispatch(any(), any(), any());
        assertThat(assistantMessages()).contains("One gap is open.");
        assertThat(state().isWorkerWaiting()).isTrue();
    }

    @Test
    void withoutAWaitingWorker_theWorkerIntentIsNotOffered() {
        WonkoState state = finishedWithWorker();
        state.setWorkerId(null);
        state.setWorkerName(null);
        processWith(state);
        routeAnswers(Map.of("intent", "question", "reason", "asks", "answer", "Fine."));

        turns(List.of(msgs(userSays("what do you think?"))));

        LightLlmRequest request = routeRequest();
        assertThat(request.getUserPrompt()).doesNotContain("A worker waits for the user");
        assertThat(JsonSchemaLight.validate(Map.of("intent", "worker", "reason", "x"), request.getSchema())
                        .valid())
                .isFalse();
    }

    // ──────────────────── finding: free text on a follow-up is no consent ────────────────────

    private static QuestionSetDto followUpSet() {
        return QuestionSetDto.builder()
                .id("offer-1")
                .round(2)
                .questions(new java.util.ArrayList<>(List.of(
                        WonkoPrompts.followUpQuestion(WonkoEngine.FOLLOWUP_KEY, "work the results in", List.of()))))
                .build();
    }

    private void processWithOffer() {
        WonkoState state = new WonkoState();
        state.setFinished(true);
        state.setFinalReport("# Review\n- Region: EU");
        state.setPendingSet(followUpSet());
        state.getFollowUps().add("work the results in");
        processWith(state);
    }

    @Test
    void followUp_freeTextDecline_isRoutedWithTheOfferClosed_andRunsNothing() {
        processWithOffer();
        routeAnswers(Map.of("intent", "question", "reason", "declines the offer", "answer", "Fine, not now."));

        turns(List.of(msgs(userAnswers(
                "offer-1",
                "nein, jetzt nicht",
                QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .value("nein, jetzt nicht")
                        .build()))));

        LightLlmRequest request = routeRequest();
        assertThat(request.getUserPrompt())
                .contains("Your offer the user did not confirm", "work the results in", "nein, jetzt nicht");
        verify(tools, never()).invokeInternal(eq("process_spawn"), any());
        assertThat(assistantMessages()).contains("Fine, not now.");
        WonkoState after = state();
        assertThat(after.getPendingSet()).isNull();
        assertThat(after.getFollowUps()).isEmpty();
        assertThat(after.getFollowUpInputs()).isEmpty();
        assertThat(after.getFollowUpReplies()).isEmpty();
    }

    @Test
    void followUp_confirmButton_runsTheOffer() {
        processWithOffer();
        routeAnswers(
                Map.of("intent", "work", "reason", "confirmed follow-up", "task", "Work the results into spec.md"));

        turns(List.of(msgs(userAnswers(
                "offer-1",
                "Confirmed",
                QuestionAnswerDto.builder().key(WonkoEngine.FOLLOWUP_KEY).build()))));

        LightLlmRequest request = routeRequest();
        assertThat(request.getUserPrompt()).doesNotContain("Your offer the user did not confirm");
        assertThat(request.getUserPrompt()).contains("## The user's message\nwork the results in");
        verify(tools).invokeInternal(eq("process_spawn"), any());
    }

    @Test
    void takeFollowUpAnswer_sortsConsentFromFreeText() {
        QuestionSetDto set = followUpSet();

        WonkoState agreed = new WonkoState();
        agreed.getFollowUps().add("work the results in");
        WonkoEngine.takeFollowUpAnswer(
                agreed,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .value(" Work the results in ")
                        .build()));
        assertThat(agreed.getFollowUpInputs()).containsExactly("work the results in");
        assertThat(agreed.getFollowUpReplies()).isEmpty();

        WonkoState declined = new WonkoState();
        declined.getFollowUps().add("work the results in");
        WonkoEngine.takeFollowUpAnswer(
                declined,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .value("no, not now")
                        .build()));
        assertThat(declined.getFollowUpInputs()).isEmpty();
        assertThat(declined.getFollowUpReplies())
                .containsExactly(new WonkoState.FollowUpReply("work the results in", "no, not now"));

        WonkoState settled = new WonkoState();
        settled.getFollowUps().add("work the results in");
        WonkoEngine.takeFollowUpAnswer(
                settled,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .outcome(QuestionOutcome.SETTLED)
                        .build()));
        assertThat(settled.getFollowUps()).isEmpty();
        assertThat(settled.getFollowUpInputs()).isEmpty();
        assertThat(settled.getFollowUpReplies()).isEmpty();
    }

    // ──────────────────── finding: a recheck waits for the open question set ────────────────────

    private void processWithOpenSetAndWorker() {
        WonkoState state = new WonkoState();
        state.setRound(1);
        state.setPendingSet(QuestionSetDto.builder()
                .id("round-1")
                .round(1)
                .questions(new java.util.ArrayList<>(List.of(QuestionDto.builder()
                        .key("region")
                        .text("Which region?")
                        .build())))
                .build());
        state.setWorkerName("wonko-work-abc");
        state.setWorkerId(WORKER_ID);
        // Measured before the worker's step — the text below differs.
        state.setSubjectFingerprint("before-the-change");
        processWith(state);
    }

    @Test
    void workerChange_whileQuestionsAreOpen_defersTheRecheck_andSaysSo() {
        processWithOpenSetAndWorker();
        subjectText = "Exports go to eu-central-1.";

        turns(List.of(msgs(workerReply("Changed the region."))));

        WonkoState after = state();
        assertThat(after.getPendingSet()).isNotNull();
        assertThat(after.getPendingSet().getId()).isEqualTo("round-1");
        assertThat(after.isRecheckDeferred()).isTrue();
        assertThat(assistantMessages())
                .contains("The subject changed — I will check it again once the open questions are answered.");
        verify(handlers, never()).check(any());
    }

    @Test
    void answersToTheOpenSet_areApplied_thenTheDeferredRecheckRuns() {
        processWithOpenSetAndWorker();
        subjectText = "Exports go to eu-central-1.";

        turns(List.of(
                msgs(workerReply("Changed the region.")),
                msgs(userAnswers(
                        "round-1",
                        "EU",
                        QuestionAnswerDto.builder()
                                .key("region")
                                .outcome(QuestionOutcome.ANSWERED)
                                .value("EU")
                                .build()))));

        // The answer was read as the answer to the set, not routed as a free message.
        verify(lightLlmService, never()).callForJson(any());
        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chat, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(m -> m.getRole() == ChatRole.USER
                        && m.getMeta() != null
                        && "round-1".equals(m.getMeta().get(ChatMessageDocument.META_QUESTIONS_ANSWERED)));
        // Then the fresh run checked the changed subject.
        verify(handlers, times(1)).check(any());
        assertThat(state().isRecheckDeferred()).isFalse();
    }

    @Test
    void workerChange_withoutOpenQuestions_rechecksAtOnce() {
        WonkoState state = finishedWithWorker();
        state.setSubjectFingerprint("before-the-change");
        processWith(state);

        turns(List.of(msgs(workerReply("Changed the region."))));

        assertThat(assistantMessages()).contains("The subject changed — checking it again.");
        verify(handlers, times(1)).check(any());
        assertThat(state().isRecheckDeferred()).isFalse();
    }
}
