package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.UsageMeasurement;
import de.mhus.vance.brain.ai.UsageSink;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.AiDecisionException.Failure;
import de.mhus.vance.brain.ai.decision.AiDecisionService;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.ToolInterruptChecker;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.llmusage.UsageKind;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ZemServiceTest {

    private static final String TENANT = "acme";
    private static final DecisionModelInfo JEV =
            new DecisionModelInfo("openrouter", "typesafe/jev-1.13", 10, 64, 255, 10, true, 32000, 0.042, null, null);

    private SettingService settings;
    private RecipeLoader recipes;
    private AiModelResolver resolver;
    private ModelCatalog catalog;
    private AiDecisionService decisions;
    private UsageSink usageSink;
    private ToolInterruptChecker interrupts;
    private ZemService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        recipes = mock(RecipeLoader.class);
        resolver = mock(AiModelResolver.class);
        catalog = mock(ModelCatalog.class);
        decisions = mock(AiDecisionService.class);
        usageSink = mock(UsageSink.class);
        interrupts = mock(ToolInterruptChecker.class);
        MetricService metrics = mock(MetricService.class);
        when(metrics.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        when(settings.getBooleanValueCascade(any(), any(), any(), eq(ZemService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(true);
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), eq("ai.provider.openrouter.apiKey")))
                .thenReturn("sk-or-test");
        when(resolver.resolveOrDefault(eq(ZemService.DEFAULT_ALIAS), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("openai", "openrouter", "typesafe/jev-1.13", false));
        when(catalog.lookupDecision(any(), any(), eq("openrouter"), eq("typesafe/jev-1.13")))
                .thenReturn(Optional.of(JEV));
        when(decisions.canServe("openrouter", "openai")).thenReturn(true);
        service = new ZemService(
                settings, recipes, resolver, catalog, decisions, new ZemThrottle(), usageSink, metrics, interrupts);
    }

    private static ZemQuestionDto noul(String name) {
        return ZemQuestionDto.builder()
                .name(name)
                .type(ZemQuestionType.NOUL)
                .instructions("Is it " + name + "?")
                .build();
    }

    private static ZemDecideRequest.ZemDecideRequestBuilder request() {
        return ZemDecideRequest.builder().tenantId(TENANT).projectId("p1").processId("proc-1");
    }

    /** Answers every question of the request with noul 0.9. */
    private static DecisionResult answerAll(DecisionRequest request, long tokens) {
        List<ZemAnswerDto> answers = request.questions().stream()
                .map(q -> ZemAnswerDto.builder()
                        .name(q.getName())
                        .type(ZemAnswerType.NOUL)
                        .noul(0.9)
                        .build())
                .toList();
        return new DecisionResult("typesafe/jev-1.13-20260917", answers, tokens, 0L, tokens * 0.042e-6, "gen-1");
    }

    // ──── prepare ──────────────────────────────────────────────────────

    @Test
    void prepare_resolves_profile_merges_inline_questions_and_reads_settings() {
        ResolvedRecipe recipe = mock(ResolvedRecipe.class);
        when(recipe.name()).thenReturn("triage");
        when(recipe.engine()).thenReturn("zem");
        when(recipe.params())
                .thenReturn(Map.of(
                        "stateSelect", List.of("message"),
                        "questions", List.of(Map.of("name", "billing", "type", "noul", "instructions", "?"))));
        when(recipes.load(TENANT, "p1", "triage")).thenReturn(Optional.of(recipe));
        when(settings.getStringValueCascade(any(), any(), any(), eq(ZemService.SETTING_TIMEOUT)))
                .thenReturn("4");
        when(settings.getStringValueCascade(any(), any(), any(), eq("ai.zem.max-concurrent.openrouter")))
                .thenReturn("3");

        ZemPreparedCall call = service.prepare(
                request().profile("triage").question(noul("urgent")).build());

        assertThat(call.profileName()).isEqualTo("triage");
        assertThat(call.questions()).extracting(ZemQuestionDto::getName).containsExactly("billing", "urgent");
        assertThat(call.stateSelect()).containsExactly("message");
        assertThat(call.config().timeoutSeconds()).isEqualTo(4);
        assertThat(call.config().apiKey()).isEqualTo("sk-or-test");
        assertThat(call.maxConcurrent()).isEqualTo(3);
        assertThat(call.rateLimitRps()).isNull();
        assertThat(call.maxRetries()).isEqualTo(ZemService.DEFAULT_MAX_RETRIES);
        assertThat(call.toString()).doesNotContain("sk-or-test");
        assertThat(call.config().toString()).doesNotContain("sk-or-test");
    }

    @Test
    void prepare_falls_back_from_instance_to_protocol_catalog_entry() {
        when(resolver.resolveOrDefault(eq("ollama:nimble"), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("ollama", "gpu-box", "nimble", false));
        DecisionModelInfo nimble = new DecisionModelInfo("ollama", "nimble", 30, 64, 26, 10, false, null, 0.0, null, 1);
        when(catalog.lookupDecision(any(), any(), eq("gpu-box"), eq("nimble"))).thenReturn(Optional.empty());
        when(catalog.lookupDecision(any(), any(), eq("ollama"), eq("nimble"))).thenReturn(Optional.of(nimble));
        when(decisions.canServe("gpu-box", "ollama")).thenReturn(true);

        ZemPreparedCall call = service.prepare(
                request().model("ollama:nimble").question(noul("q")).build());

        assertThat(call.model()).isSameAs(nimble);
        assertThat(call.maxConcurrent()).isEqualTo(1);
        assertThat(call.config().apiKey()).isNull();
    }

    @Test
    void prepare_failures_come_before_any_provider_call() {
        when(settings.getBooleanValueCascade(any(), any(), any(), eq(ZemService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(false);
        assertReason(() -> service.prepare(request().question(noul("q")).build()), ZemException.Reason.DISABLED);
        setUp();

        when(recipes.load(any(), any(), eq("missing"))).thenReturn(Optional.empty());
        assertReason(() -> service.prepare(request().profile("missing").build()), ZemException.Reason.INVALID_PROFILE);

        ResolvedRecipe chat = mock(ResolvedRecipe.class);
        when(chat.engine()).thenReturn("ford");
        when(recipes.load(any(), any(), eq("ford"))).thenReturn(Optional.of(chat));
        assertReason(() -> service.prepare(request().profile("ford").build()), ZemException.Reason.INVALID_PROFILE);

        when(resolver.resolveOrDefault(eq("default:fast"), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("openai", "openai", "gpt-5", false));
        assertReason(
                () -> service.prepare(
                        request().model("default:fast").question(noul("q")).build()),
                ZemException.Reason.INVALID_CHOICE);

        assertReason(() -> service.prepare(request().build()), ZemException.Reason.INVALID_QUESTION);
        verify(decisions, never()).decide(any(), any());
    }

    // ──── decide ───────────────────────────────────────────────────────

    @Test
    void one_off_decision_books_one_ledger_write_with_catalog_pricing() {
        when(decisions.decide(any(), any())).thenAnswer(inv -> answerAll(inv.getArgument(1), 456));

        ZemDecisionDto decision =
                service.decide(request().question(noul("billing")).build(), Map.of("m", "x"));

        assertThat(decision.getModel()).isEqualTo("typesafe/jev-1.13-20260917");
        assertThat(decision.getInputTokens()).isEqualTo(456L);
        assertThat(decision.getAnswers().get(0).getConfidence())
                .isCloseTo(0.8, org.assertj.core.api.Assertions.within(1e-9));

        ArgumentCaptor<CallAttribution> who = ArgumentCaptor.forClass(CallAttribution.class);
        ArgumentCaptor<UsageMeasurement> what = ArgumentCaptor.forClass(UsageMeasurement.class);
        verify(usageSink).onCall(who.capture(), what.capture());
        assertThat(who.getValue().caller()).isEqualTo(LlmUsageService.CALLER_ZEM);
        assertThat(who.getValue().processId()).isEqualTo("proc-1");
        assertThat(what.getValue().kind()).isEqualTo(UsageKind.DECISION);
        assertThat(what.getValue().tokensIn()).isEqualTo(456);
        assertThat(what.getValue().images()).isEqualTo(1);
        assertThat(what.getValue().pricing().inputPerMTok()).isEqualTo(0.042);
        assertThat(what.getValue().outcome()).isEqualTo(UsageOutcome.SUCCESS);
    }

    @Test
    void questions_above_the_model_limit_are_split_and_merged_in_order() {
        DecisionModelInfo small =
                new DecisionModelInfo("openrouter", "typesafe/jev-1.13", 10, 2, 255, 10, true, null, null, null, null);
        when(catalog.lookupDecision(any(), any(), eq("openrouter"), eq("typesafe/jev-1.13")))
                .thenReturn(Optional.of(small));
        List<Integer> callSizes = new ArrayList<>();
        when(decisions.decide(any(), any())).thenAnswer(inv -> {
            DecisionRequest r = inv.getArgument(1);
            callSizes.add(r.questions().size());
            return answerAll(r, 100);
        });
        ZemDecideRequest.ZemDecideRequestBuilder b = request();
        IntStream.range(0, 5).forEach(i -> b.question(noul("q" + i)));

        ZemDecisionDto decision = service.decide(b.build(), "state");

        assertThat(callSizes).containsExactly(2, 2, 1);
        assertThat(decision.getAnswers())
                .extracting(ZemAnswerDto::getName)
                .containsExactly("q0", "q1", "q2", "q3", "q4");
        assertThat(decision.getInputTokens()).isEqualTo(300L);
        assertThat(decision.getCostUsd()).isCloseTo(300 * 0.042e-6, org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    void retryable_failures_are_retried_with_the_providers_retry_after() {
        when(decisions.decide(any(), any()))
                .thenThrow(new AiDecisionException(Failure.RATE_LIMITED, "slow down", 429, Duration.ZERO, null))
                .thenThrow(new AiDecisionException(Failure.PROVIDER_UNAVAILABLE, "529", 529, Duration.ZERO, null))
                .thenAnswer(inv -> answerAll(inv.getArgument(1), 10));

        ZemDecisionDto decision = service.decide(request().question(noul("q")).build(), "s");

        assertThat(decision.getAnswers()).hasSize(1);
        verify(decisions, times(3)).decide(any(), any());
        verify(interrupts, times(3)).throwIfHalted("proc-1");
    }

    @Test
    void exhausted_retries_and_hard_failures_are_mapped_and_booked_as_failed() {
        when(settings.getStringValueCascade(any(), any(), any(), eq(ZemService.SETTING_MAX_RETRIES)))
                .thenReturn("1");
        when(decisions.decide(any(), any()))
                .thenThrow(new AiDecisionException(Failure.RATE_LIMITED, "slow down", 429, Duration.ZERO, null));

        assertReason(
                () -> service.decide(request().question(noul("q")).build(), "s"), ZemException.Reason.RATE_LIMITED);
        verify(decisions, times(2)).decide(any(), any());

        ArgumentCaptor<UsageMeasurement> what = ArgumentCaptor.forClass(UsageMeasurement.class);
        verify(usageSink).onCall(any(), what.capture());
        assertThat(what.getValue().outcome()).isEqualTo(UsageOutcome.FAILED);
    }

    @Test
    void non_retryable_failure_is_not_retried() {
        when(decisions.decide(any(), any()))
                .thenThrow(new AiDecisionException(Failure.INVALID_REQUEST, "too many choices"));

        assertReason(
                () -> service.decide(request().question(noul("q")).build(), "s"), ZemException.Reason.INVALID_REQUEST);
        verify(decisions, times(1)).decide(any(), any());
    }

    @Test
    void state_must_be_string_map_or_list() {
        ZemPreparedCall call = service.prepare(request().question(noul("q")).build());

        assertReason(() -> service.decide(call, 42, null), ZemException.Reason.INVALID_STATE);
        assertReason(() -> service.decide(call, null, null), ZemException.Reason.INVALID_STATE);
    }

    @Test
    void state_is_projected_before_the_call() {
        ArgumentCaptor<DecisionRequest> sent = ArgumentCaptor.forClass(DecisionRequest.class);
        when(decisions.decide(any(), sent.capture())).thenAnswer(inv -> answerAll(inv.getArgument(1), 1));

        service.decide(
                request().question(noul("q")).stateSelect(List.of("message")).build(),
                Map.of("message", "hi", "noise", "x"));

        assertThat(sent.getValue().state()).isEqualTo(Map.of("message", "hi"));
    }

    // ──── bulk path ────────────────────────────────────────────────────

    @Test
    void accumulator_defers_booking_to_flush_and_books_once_per_outcome() {
        when(decisions.decide(any(), any()))
                .thenAnswer(inv -> answerAll(inv.getArgument(1), 100))
                .thenAnswer(inv -> answerAll(inv.getArgument(1), 100))
                .thenThrow(new AiDecisionException(Failure.INVALID_REQUEST, "bad record"))
                .thenAnswer(inv -> answerAll(inv.getArgument(1), 100));
        ZemPreparedCall call = service.prepare(request().question(noul("q")).build());
        ZemUsageAccumulator usage = service.openAccumulator(call);

        service.decide(call, "r1", usage);
        service.decide(call, "r2", usage);
        assertReason(() -> service.decide(call, "r3", usage), ZemException.Reason.INVALID_REQUEST);
        service.decide(call, "r4", usage);
        verify(usageSink, never()).onCall(any(), any());
        assertThat(usage.pendingDecisions()).isEqualTo(3);

        service.flush(usage);

        ArgumentCaptor<UsageMeasurement> what = ArgumentCaptor.forClass(UsageMeasurement.class);
        verify(usageSink, times(2)).onCall(any(), what.capture());
        UsageMeasurement ok = what.getAllValues().get(0);
        assertThat(ok.outcome()).isEqualTo(UsageOutcome.SUCCESS);
        assertThat(ok.images()).isEqualTo(3);
        assertThat(ok.tokensIn()).isEqualTo(300);
        assertThat(what.getAllValues().get(1).outcome()).isEqualTo(UsageOutcome.FAILED);
        assertThat(what.getAllValues().get(1).images()).isEqualTo(1);

        service.flush(usage); // nothing pending — no empty writes
        verify(usageSink, times(2)).onCall(any(), any());
    }

    @Test
    void accumulator_of_another_call_is_refused() {
        ZemPreparedCall a = service.prepare(request().question(noul("q")).build());
        ZemPreparedCall b = service.prepare(request().question(noul("q")).build());

        assertThatThrownBy(() -> service.decide(a, "s", service.openAccumulator(b)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provider_failure_classes_map_to_public_reasons() {
        assertThat(ZemService.map(new AiDecisionException(Failure.MODEL_NOT_FOUND, "x"))
                        .reason())
                .isEqualTo(ZemException.Reason.INVALID_CHOICE);
        assertThat(ZemService.map(new AiDecisionException(Failure.PROTOCOL, "x"))
                        .reason())
                .isEqualTo(ZemException.Reason.PROVIDER_ERROR);
        assertThat(ZemService.map(new AiDecisionException(Failure.AUTH, "x")).reason())
                .isEqualTo(ZemException.Reason.AUTH);
        assertThat(ZemService.map(new AiDecisionException(Failure.TIMEOUT, "x")).retryable())
                .isTrue();
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ZemException.Reason reason) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ZemException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
