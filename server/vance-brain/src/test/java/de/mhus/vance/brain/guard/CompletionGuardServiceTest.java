package de.mhus.vance.brain.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompletionGuardServiceTest {

    @Mock private RecipeResolver recipeResolver;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private ProcessEventEmitter eventEmitter;
    @Mock private ScriptExecutor scriptExecutor;
    @Mock private DocumentService documentService;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private ProgressEmitter progressEmitter;
    @Mock private NotificationService notificationService;
    @Mock private SessionService sessionService;
    @Mock private ObjectProvider<ThinkEngineService> thinkEngineProvider;

    private CompletionGuardService service;

    @BeforeEach
    void setUp() {
        service = new CompletionGuardService(
                recipeResolver, thinkProcessService, chatMessageService, eventEmitter,
                scriptExecutor, documentService, toolDispatcher, progressEmitter,
                notificationService, sessionService, thinkEngineProvider,
                new MetricService(new SimpleMeterRegistry()));
        when(recipeResolver.resolve(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());
        when(thinkProcessService.incrementGuardRounds(anyString())).thenReturn(1);
        when(thinkProcessService.appendPending(anyString(), any(PendingMessageDocument.class)))
                .thenReturn(true);
        when(documentService.lookupCascade(any(), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/guards/g.js", "return;", LookupResult.Source.RESOURCE, null)));
    }

    /** A process carrying an active runtime-override guard (script path). */
    private ThinkProcessDocument guarded(int rounds) {
        return ThinkProcessDocument.builder()
                .id("p1").tenantId("acme").projectId("proj").sessionId("s1")
                .guardScriptOverride("_vance/guards/g.js")
                .guardRounds(rounds)
                .build();
    }

    /** Makes the mocked script executor simulate a script that calls continueWith. */
    private void scriptFires(String prompt) {
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            req.guardApi().continueWith(prompt);
            return new ScriptResult(null, Duration.ZERO);
        });
    }

    @Test
    void noGuards_isNoop() {
        ThinkProcessDocument plain = ThinkProcessDocument.builder().id("p1")
                .tenantId("acme").projectId("proj").sessionId("s1").build();

        GuardEvaluation result = service.evaluate(plain, "done", true);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void scriptContinues_injectsSchedulesAndIncrements() {
        scriptFires("Did you build and update the spec?");

        GuardEvaluation result = service.evaluate(guarded(0), "I changed the code.", true);

        assertThat(result.fired()).isTrue();
        assertThat(result.reason()).isEqualTo("Did you build and update the spec?");
        verify(thinkProcessService).incrementGuardRounds("p1");
        verify(thinkProcessService).appendPending(anyString(), any(PendingMessageDocument.class));
        verify(eventEmitter).scheduleTurn("p1");
    }

    @Test
    void scriptDoesNotContinue_passesWithoutInjection() {
        when(scriptExecutor.run(any())).thenReturn(new ScriptResult(null, Duration.ZERO));

        GuardEvaluation result = service.evaluate(guarded(0), "all done, built and committed", true);

        assertThat(result.fired()).isFalse();
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void roundCapReached_skipsScript() {
        // runtime guard maxRounds = RUNTIME_MAX_ROUNDS (3); already at 3.
        GuardEvaluation result = service.evaluate(guarded(3), "done", true);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void scriptError_failsOpen() {
        when(scriptExecutor.run(any())).thenThrow(new ScriptExecutionException(
                ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "boom"));

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isFalse();
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void missingScript_failsOpen() {
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void terminateStop_doesNotFireStopOnlyGuard() {
        // runtime guard trigger defaults to STOP → must not fire on terminate.
        GuardEvaluation result = service.evaluate(guarded(0), "", /*naturalStop*/ false);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void capAwareContinue_refusesPastCap() {
        // Script tries to continue but the process is already at the cap:
        // continueWith must return false and nothing is injected.
        scriptFires("nudge");

        GuardEvaluation result = service.evaluate(guarded(3), "done", true);

        // guarded(3) is skipped by the pre-check (rounds >= maxRounds), so the
        // script never runs — assert the cap holds at the outer gate too.
        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
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
        SteerMessage injected = new SteerMessage.UserChatInput(
                Instant.now(), null, CompletionGuardService.INJECT_SENDER,
                "[completion-guard] Did you build?");

        service.resetIfUserTurn(guarded(2), List.of(injected));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }

    @Test
    void resetIfUserTurn_zeroRounds_doesNotResetCounter() {
        SteerMessage userMsg = new SteerMessage.UserChatInput(
                Instant.now(), null, "alice", "hi");

        service.resetIfUserTurn(guarded(0), List.of(userMsg));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }
}
