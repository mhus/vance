package de.mhus.vance.brain.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.recipe.GuardConfig;
import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
class ShootyGuardServiceTest {

    @Mock
    private RecipeResolver recipeResolver;

    @Mock
    private ThinkProcessService thinkProcessService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ProcessEventEmitter eventEmitter;

    @Mock
    private ScriptExecutor scriptExecutor;

    @Mock
    private DocumentService documentService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private SecurityContextFactory contextFactory;

    @Mock
    private ToolDispatcher toolDispatcher;

    @Mock
    private ProgressEmitter progressEmitter;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SessionService sessionService;

    @Mock
    private ObjectProvider<ThinkEngineService> thinkEngineProvider;

    @Mock
    private ObjectProvider<SkillSteerProcessor> skillSteerProvider;

    private ShootyGuardService service;

    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new ShootyGuardService(
                recipeResolver,
                thinkProcessService,
                chatMessageService,
                eventEmitter,
                scriptExecutor,
                documentService,
                new DocumentRefResolver(),
                permissionService,
                contextFactory,
                toolDispatcher,
                progressEmitter,
                notificationService,
                sessionService,
                thinkEngineProvider,
                skillSteerProvider,
                new MetricService(registry));
        when(recipeResolver.resolve(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());
        when(thinkProcessService.incrementGuardRounds(anyString())).thenReturn(1);
        when(thinkProcessService.appendPending(anyString(), any(PendingMessageDocument.class)))
                .thenReturn(true);
        when(documentService.lookupCascade(any(), any(), any()))
                .thenReturn(Optional.of(
                        new LookupResult("_vance/guards/g.js", "return;", LookupResult.Source.RESOURCE, null)));
    }

    /** A process carrying an active runtime-override guard (script path). */
    private ThinkProcessDocument guarded(int rounds) {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
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
        ThinkProcessDocument plain = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .build();

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
    void scriptError_failsOpen_countsScriptErrorNotPassed() {
        when(scriptExecutor.run(any()))
                .thenThrow(new ScriptExecutionException(ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "boom"));

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isFalse();
        verify(eventEmitter, never()).scheduleTurn(anyString());
        // "passed" says every applicable guard actually passed — a script
        // error is script_error, not a pass (fail-open ≠ guard agreed).
        assertThat(outcomeCount("script_error")).isEqualTo(1.0);
        assertThat(outcomeCount("passed")).isZero();
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

    /** A process whose guard script lives in another project of the same tenant. */
    private ThinkProcessDocument crossProjectGuarded() {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .guardScriptOverride("//other/guards/g.js")
                .guardRounds(0)
                .build();
    }

    /** Binds session {@code s1} to a named owner, so the ref check has an identity. */
    private void sessionOwnedBy(String userId) {
        when(sessionService.findBySessionId("s1"))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId("s1")
                        .tenantId("acme")
                        .projectId("proj")
                        .userId(userId)
                        .build()));
    }

    @Test
    void crossProjectScript_withoutRead_isNotLoaded() {
        sessionOwnedBy("alice");
        doThrow(new PermissionDeniedException(
                        SecurityContext.SYSTEM, new Resource.Document("acme", "other", "guards/g.js"), Action.READ))
                .when(permissionService)
                .enforce(any(), any(), eq(Action.READ));
        scriptFires("nudge");

        GuardEvaluation result = service.evaluate(crossProjectGuarded(), "done", true);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void crossProjectScript_withRead_isLoaded() {
        sessionOwnedBy("alice");
        scriptFires("nudge");

        GuardEvaluation result = service.evaluate(crossProjectGuarded(), "done", true);

        assertThat(result.fired()).isTrue();
        verify(permissionService)
                .enforce(any(), eq(new Resource.Document("acme", "other", "guards/g.js")), eq(Action.READ));
    }

    @Test
    void crossProjectScript_withoutSessionOwner_isRefused() {
        // No session owner means forToolSubject would yield SYSTEM, which
        // passes every enforce — the check must not silently become a no-op
        // on exactly the headless path.
        when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());
        scriptFires("nudge");

        GuardEvaluation result = service.evaluate(crossProjectGuarded(), "done", true);

        assertThat(result.fired()).isFalse();
        verify(scriptExecutor, never()).run(any());
        verify(permissionService, never()).enforce(any(), any(), any());
    }

    @Test
    void inProjectScript_isLoadedWithoutAPermissionRoundTrip() {
        sessionOwnedBy("alice");
        scriptFires("nudge");

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isTrue();
        verify(permissionService, never()).enforce(any(), any(), any());
    }

    @Test
    void sessionlessScratch_isVisibleToRemoveAndView() {
        // A session-less process falls back to its loop scratch for the
        // session scope. Read, inspect and remove must agree on that —
        // //guard status session del used to report "not present" for a
        // key the script could still read.
        ThinkProcessDocument headless = ThinkProcessDocument.builder()
                .id("p9")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("")
                .build();

        service.putScratch(headless, true, "asked", "yes");

        assertThat(service.sessionScratchView(headless)).containsEntry("asked", "yes");
        assertThat(service.removeScratch(headless, true, "asked")).isTrue();
        assertThat(service.sessionScratchView(headless)).isEmpty();
    }

    @Test
    void sessionlessScratch_isNotSharedBetweenProcesses() {
        // The empty sessionId must not become a shared map key — every
        // headless worker on the pod would otherwise see the same flags.
        ThinkProcessDocument one = ThinkProcessDocument.builder()
                .id("p9")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("")
                .build();
        ThinkProcessDocument two = ThinkProcessDocument.builder()
                .id("p10")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("")
                .build();

        service.putScratch(one, true, "asked", "yes");

        assertThat(service.sessionScratchView(two)).isEmpty();
    }

    @Test
    void resetIfUserTurn_genuineUserInput_resetsRounds() {
        SteerMessage userMsg =
                new SteerMessage.UserChatInput(Instant.now(), null, "alice", "please also fix the login bug");

        service.resetIfUserTurn(guarded(2), List.of(userMsg));

        verify(thinkProcessService).resetGuardRounds("p1");
    }

    @Test
    void resetIfUserTurn_onlyGuardInjection_doesNotReset() {
        SteerMessage injected = new SteerMessage.UserChatInput(
                Instant.now(), null, ShootyGuardService.INJECT_SENDER, "[completion-guard] Did you build?");

        service.resetIfUserTurn(guarded(2), List.of(injected));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }

    @Test
    void resetIfUserTurn_zeroRounds_doesNotResetCounter() {
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.resetIfUserTurn(guarded(0), List.of(userMsg));

        verify(thinkProcessService, never()).resetGuardRounds(anyString());
    }

    // ─────────────────── START point ───────────────────

    /** A recipe carrying the given guards, resolved for {@code recipeName}. */
    private void recipeWith(GuardConfig... guards) {
        ResolvedRecipe recipe = new ResolvedRecipe(
                "coding",
                "test recipe",
                "frankie",
                java.util.Map.of(),
                null,
                PromptMode.APPEND,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                List.of(),
                null,
                List.of(),
                false,
                false,
                false,
                false,
                null,
                null,
                List.of(),
                List.of(guards),
                List.of(),
                RecipeSource.RESOURCE);
        when(recipeResolver.resolve(anyString(), anyString(), anyString())).thenReturn(Optional.of(recipe));
    }

    private ThinkProcessDocument recipeProcess() {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .recipeName("coding")
                .build();
    }

    @Test
    void startGuard_firesPerGenuineUserTurn_withTurnInputAsTask() {
        recipeWith(GuardConfig.scriptBody("vance.guard.activateSkill('review-mode');", false, GuardPoint.START, 1));
        AtomicReference<ScriptRequest> seen = new AtomicReference<>();
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "refactor the login flow");

        service.runStartGuards(recipeProcess(), List.of(userMsg));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().guardApi().point).isEqualTo("start");
        // The turn's user input is the task at the start point — not the
        // process's first history message.
        assertThat(seen.get().guardApi().task).isEqualTo("refactor the login flow");
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void startGuard_ignoresGuardInjectedTurns() {
        recipeWith(GuardConfig.scriptBody("vance.guard.activateSkill('x');", false, GuardPoint.START, 1));
        SteerMessage injected = new SteerMessage.UserChatInput(
                Instant.now(), null, ShootyGuardService.INJECT_SENDER, "[completion-guard] did you build?");

        service.runStartGuards(recipeProcess(), List.of(injected));

        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void startGuard_stopGuard_doesNotFireAtStart() {
        recipeWith(GuardConfig.scriptBody("vance.guard.continueWith('x');", false, GuardPoint.STOP, 1));
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.runStartGuards(recipeProcess(), List.of(userMsg));

        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void startGuard_scriptError_failsOpen() {
        recipeWith(GuardConfig.scriptBody("throw new Error('x')", false, GuardPoint.START, 1));
        when(scriptExecutor.run(any()))
                .thenThrow(new ScriptExecutionException(ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "boom"));
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        // Fail-open: the turn proceeds, the exception does not propagate.
        service.runStartGuards(recipeProcess(), List.of(userMsg));

        // The error is script_error, not also "passed" — same vocabulary
        // as the command gate, which never double-counts either.
        assertThat(outcomeCount("script_error")).isEqualTo(1.0);
        assertThat(outcomeCount("passed")).isZero();
    }

    @Test
    void guardsOnTurnStart_resetsBudgetAndLoopScratch_beforeStartGuards() {
        // The combined anchor owns the mandatory ordering (shooty.md §2.2):
        // budget reset + loop-scratch wipe first, THEN the start guards —
        // the script starts on a clean slate. Ford and Frankie call this
        // anchor too; this is the contract that keeps them honest.
        recipeWith(GuardConfig.scriptBody("vance.guard.activateSkill('x');", false, GuardPoint.START, 1));
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .recipeName("coding")
                .guardRounds(2)
                .build();
        service.putScratch(process, /*session*/ false, "asked", "yes");
        AtomicReference<ScriptRequest> seen = new AtomicReference<>();
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.guardsOnTurnStart(process, List.of(userMsg));

        // Budget reset happened, and it happened BEFORE the script ran.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(thinkProcessService, scriptExecutor);
        order.verify(thinkProcessService).resetGuardRounds("p1");
        order.verify(scriptExecutor).run(any());
        // The loop scratch was wiped before the script read it.
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().guardApi().loopValues.get("asked")).isNull();
    }

    /** The count of {@code vance.guard.evaluations} for one outcome tag. */
    private double outcomeCount(String outcome) {
        io.micrometer.core.instrument.Counter c =
                registry.find("vance.guard.evaluations").tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    // ─────────── START: turn-prompt replacement ───────────

    @Test
    void startGuard_setTurnPrompt_isStoredForTheTurn() {
        recipeWith(GuardConfig.scriptBody("vance.guard.setTurnPrompt('custom framing');", false, GuardPoint.START, 1));
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            req.guardApi().setTurnPrompt("custom framing");
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.runStartGuards(recipeProcess(), List.of(userMsg));

        assertThat(service.turnPromptFor(recipeProcess())).isEqualTo("custom framing");
    }

    @Test
    void startGuard_nextUserTurn_clearsStaleTurnPrompt_byDefault() {
        // Turn 1: the start guard replaces the prompt. Turn 2: the start
        // guard does NOT set a prompt — by default the prompt is not
        // manipulated, so the stale replacement must be gone. One answer
        // with a flag instead of re-stubbing: a second when(...) would
        // execute this very answer with a null matcher argument.
        AtomicBoolean setPrompt = new AtomicBoolean(true);
        recipeWith(GuardConfig.scriptBody("vance.guard.setTurnPrompt('v1');", false, GuardPoint.START, 1));
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            if (setPrompt.get() && req != null) {
                req.guardApi().setTurnPrompt("v1");
            }
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");
        service.runStartGuards(recipeProcess(), List.of(userMsg));
        assertThat(service.turnPromptFor(recipeProcess())).isEqualTo("v1");

        setPrompt.set(false);
        SteerMessage next = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "next request");
        service.runStartGuards(recipeProcess(), List.of(next));

        assertThat(service.turnPromptFor(recipeProcess())).isNull();
    }

    @Test
    void startGuard_lastSetTurnPromptWins() {
        recipeWith(GuardConfig.scriptBody("vance.guard.setTurnPrompt('a');", false, GuardPoint.START, 1));
        AtomicReference<String> seen = new AtomicReference<>();
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            req.guardApi().setTurnPrompt("first");
            req.guardApi().setTurnPrompt("second");
            seen.set(req.guardApi().point);
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.runStartGuards(recipeProcess(), List.of(userMsg));

        assertThat(seen.get()).isEqualTo("start");
        assertThat(service.turnPromptFor(recipeProcess())).isEqualTo("second");
    }

    @Test
    void noStartGuard_noTurnPrompt_byDefault() {
        // A process with only a STOP guard (the runtime override) never
        // touches the turn prompt.
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.runStartGuards(guarded(0), List.of(userMsg));

        assertThat(service.turnPromptFor(guarded(0))).isNull();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void stopGuard_setTurnPrompt_failsOpen() {
        // setTurnPrompt is a START-only action — a stop-guard script
        // calling it is a script bug; the throw is caught and the yield
        // proceeds (fail-open, like every other stop-script error).
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            req.guardApi().setTurnPrompt("nope");
            return new ScriptResult(null, Duration.ZERO);
        });

        GuardEvaluation result = service.evaluate(guarded(0), "done", true);

        assertThat(result.fired()).isFalse();
        assertThat(service.turnPromptFor(guarded(0))).isNull();
    }

    // ─────────────────── COMMAND point ───────────────────

    private EngineCommand command(String verb) {
        return new EngineCommand(verb, java.util.Map.of("text", "args"));
    }

    /** Makes the mocked script executor simulate a script that denies. */
    private void scriptDenies(String reason) {
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            req.guardApi().deny(reason);
            return new ScriptResult(null, Duration.ZERO);
        });
    }

    @Test
    void commandGuard_denies_withReason() {
        recipeWith(GuardConfig.scriptBody("vance.guard.deny('unsafe');", false, GuardPoint.COMMAND, 1));
        scriptDenies("mode change is unsafe here");

        EngineCommandResult result = service.gateCommand(recipeProcess(), command("mode.set"));

        assertThat(result).isNotNull();
        assertThat(result.deniedByGuard()).isTrue();
        assertThat(result.outcome()).isEqualTo(de.mhus.vance.api.command.EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("mode change is unsafe here");
    }

    @Test
    void commandGuard_scriptError_failsClosed() {
        recipeWith(GuardConfig.scriptBody("throw new Error('x')", false, GuardPoint.COMMAND, 1));
        when(scriptExecutor.run(any()))
                .thenThrow(new ScriptExecutionException(ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "boom"));

        EngineCommandResult result = service.gateCommand(recipeProcess(), command("mode.set"));

        // Fail-closed, hard: a failing guard script denies the command.
        assertThat(result).isNotNull();
        assertThat(result.deniedByGuard()).isTrue();
        assertThat(result.message()).contains("fail-closed");
    }

    @Test
    void commandGuard_missingScript_failsClosed() {
        recipeWith(GuardConfig.scriptPath("_vance/guards/missing.js", false, GuardPoint.COMMAND, 1));
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());

        EngineCommandResult result = service.gateCommand(recipeProcess(), command("mode.set"));

        assertThat(result).isNotNull();
        assertThat(result.deniedByGuard()).isTrue();
    }

    @Test
    void commandGuard_passes_whenScriptDoesNotDeny() {
        recipeWith(GuardConfig.scriptBody("vance.guard.command.name;", false, GuardPoint.COMMAND, 1));
        AtomicReference<ScriptRequest> seen = new AtomicReference<>();
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return new ScriptResult(null, Duration.ZERO);
        });

        EngineCommandResult result = service.gateCommand(recipeProcess(), command("mode.set"));

        assertThat(result).isNull();
        assertThat(seen.get().guardApi().point).isEqualTo("command");
        assertThat(seen.get().guardApi().command.get("name")).isEqualTo("mode.set");
        assertThat(seen.get().guardApi().command.get("args")).isEqualTo(java.util.Map.of("text", "args"));
    }

    @Test
    void commandGuard_stopGuard_notConsultedForCommands() {
        // The runtime-override guard (STOP) must not gate commands — a
        // process with a completion guard only keeps its old behavior.
        EngineCommandResult result = service.gateCommand(guarded(0), command("mode.set"));

        assertThat(result).isNull();
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void commandGuard_guardOriginatedCommands_bypassTheGate() {
        // Re-entrancy: a command fired from inside a guard run (here: the
        // start guard's script Answer re-enters gateCommand) must bypass
        // the COMMAND gate — the guard does not judge its own actions.
        recipeWith(
                GuardConfig.scriptBody("vance.guard.activateSkill('x');", false, GuardPoint.START, 1),
                GuardConfig.scriptBody("vance.guard.deny('unsafe');", false, GuardPoint.COMMAND, 1));
        AtomicReference<EngineCommandResult> gateResult = new AtomicReference<>(null);
        when(scriptExecutor.run(any())).thenAnswer(inv -> {
            ScriptRequest req = inv.getArgument(0);
            if ("start".equals(req.guardApi().point)) {
                gateResult.set(service.gateCommand(recipeProcess(), command("mode.set")));
            }
            return new ScriptResult(null, Duration.ZERO);
        });
        SteerMessage userMsg = new SteerMessage.UserChatInput(Instant.now(), null, "alice", "hi");

        service.runStartGuards(recipeProcess(), List.of(userMsg));

        // The COMMAND guard would deny — but the command originated from a
        // guard run, so the gate must have let it through (null = proceed).
        assertThat(gateResult.get()).isNull();
    }
}
