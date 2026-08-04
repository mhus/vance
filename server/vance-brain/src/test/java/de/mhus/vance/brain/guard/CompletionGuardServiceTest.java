package de.mhus.vance.brain.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompletionGuardServiceTest {

    @Mock private RecipeResolver recipeResolver;
    @Mock private LightLlmService lightLlm;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private ProcessEventEmitter eventEmitter;

    private CompletionGuardService service;

    @BeforeEach
    void setUp() {
        service = new CompletionGuardService(
                recipeResolver, lightLlm, thinkProcessService, chatMessageService,
                eventEmitter, new MetricService(new SimpleMeterRegistry()));
        when(recipeResolver.resolve(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        when(thinkProcessService.incrementGuardRounds(anyString())).thenReturn(1);
        when(thinkProcessService.appendPending(anyString(), any(PendingMessageDocument.class)))
                .thenReturn(true);
    }

    /** A process carrying an active runtime-override guard (judge + prompt). */
    private ThinkProcessDocument guarded(int rounds) {
        return ThinkProcessDocument.builder()
                .id("p1").tenantId("acme").projectId("proj").sessionId("s1")
                .guardJudgeOverride("Is a dev task completed?")
                .guardPromptOverride("Did you build and update the spec?")
                .guardRounds(rounds)
                .build();
    }

    @Test
    void noGuards_isNoop() {
        ThinkProcessDocument plain = ThinkProcessDocument.builder().id("p1")
                .tenantId("acme").projectId("proj").sessionId("s1").build();

        GuardEvaluation result = service.evaluate(plain, "done", true);

        assertThat(result.fired()).isFalse();
        verify(thinkProcessService, never()).incrementGuardRounds(anyString());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void judgeFires_injectsSchedulesAndIncrements() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("fire", Boolean.TRUE, "reason", "no build ran"));

        GuardEvaluation result = service.evaluate(guarded(0), "I changed the code.", true);

        assertThat(result.fired()).isTrue();
        assertThat(result.reason()).isEqualTo("no build ran");
        verify(thinkProcessService).incrementGuardRounds("p1");
        verify(thinkProcessService).appendPending(anyString(), any(PendingMessageDocument.class));
        verify(eventEmitter).scheduleTurn("p1");
    }

    @Test
    void judgeDoesNotFire_passesWithoutInjection() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("fire", Boolean.FALSE));

        GuardEvaluation result = service.evaluate(guarded(0), "all done, built and committed", true);

        assertThat(result.fired()).isFalse();
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void roundCapReached_skipsJudge() {
        // runtime guard maxRounds = RUNTIME_MAX_ROUNDS (3); already at 3.
        GuardEvaluation result = service.evaluate(guarded(3), "done", true);

        assertThat(result.fired()).isFalse();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void judgeError_failsOpen() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new RuntimeException("provider exhausted"));

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isFalse();
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void terminateStop_doesNotFireStopOnlyGuard() {
        // runtime guard trigger defaults to STOP → must not fire on terminate.
        GuardEvaluation result = service.evaluate(guarded(0), "", /*naturalStop*/ false);

        assertThat(result.fired()).isFalse();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resetIfUserTurn_genuineUserInput_resetsRounds() {
        SteerMessage userMsg = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "please also fix the login bug");

        service.resetIfUserTurn(guarded(2), List.of(userMsg));

        verify(thinkProcessService).resetGuardRounds("p1");
    }

    @Test
    void resetIfUserTurn_onlyGuardInjection_doesNotReset() {
        // The guard's own follow-up must not refill its own budget.
        SteerMessage injected = new SteerMessage.UserChatInput(
                Instant.now(), null, CompletionGuardService.INJECT_SENDER,
                "[completion-guard] Did you build?");

        service.resetIfUserTurn(guarded(2), List.of(injected));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }

    @Test
    void resetIfUserTurn_zeroRounds_shortCircuits() {
        SteerMessage userMsg = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "hi");

        service.resetIfUserTurn(guarded(0), List.of(userMsg));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }
}
