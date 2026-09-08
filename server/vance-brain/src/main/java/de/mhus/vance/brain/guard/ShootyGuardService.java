package de.mhus.vance.brain.guard;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.recipe.GuardConfig;
import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.script.GuardScriptHost;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardScratchApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptHostException;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.document.DocumentRefContext;
import de.mhus.vance.shared.document.DocumentRefException;
import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Shooty — the engine-agnostic guard system (formerly the completion
 * guard). Guard scripts run at hook <b>points</b> via the shared
 * {@link ScriptExecutor}; the script decides judge + action imperatively
 * through the {@code vance.guard.*} surface. See
 * {@code planning/shooty.md}:
 *
 * <ul>
 *   <li><b>STOP / TERMINATE</b> — an engine's yield point (the classic
 *       completion guard, unchanged). {@code vance.guard.continueWith(prompt)}
 *       injects a follow-up into the process's own pending queue and
 *       schedules a lane turn. <b>Fail-open</b>: a script error never
 *       blocks the engine.</li>
 *   <li><b>START</b> — once per genuine user turn, right after
 *       {@link #resetIfUserTurn} (clean budget, clean loop scratch).
 *       Typical action: {@code vance.guard.activateSkill(...)}. Fail-open.</li>
 *   <li><b>COMMAND</b> — gates engine-command dispatch before the handler
 *       runs (fail-<b>closed</b>, hard: a denied or failing script fails
 *       the command with {@link EngineCommandResult#guardDenied}, and the
 *       skill command runner aborts the remaining sequence).</li>
 * </ul>
 *
 * <p>Guards come from the recipe {@code guard:} block plus an additive
 * per-process runtime override ({@code guardScriptOverride}). Backstops:
 * a per-guard {@code maxRounds} cap at the yield points against the
 * process's persistent {@code guardRounds} counter (enforced by the
 * cap-aware {@link GuardScriptHost}), the script timeout, and the
 * per-point fail strategy. Transient per-loop / per-session scratch
 * stores ({@code vance.guard.loopValues} / {@code sessionValues}) are
 * shared across all points of a process — a start guard's flags are
 * readable by its stop guard.
 *
 * <p>The guard does not guard itself: a re-entrancy marker
 * ({@link #inGuardRun()}) is set for the duration of every guard script
 * run (inheritable, because GraalJS evaluates on a watchdog child
 * thread), and the COMMAND gate skips everything that originates from a
 * guard run — LLM calls, commands fired by skill activation, future
 * hooks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShootyGuardService {

    /** Sender id stamped on injected pending messages. */
    static final String INJECT_SENDER = "_guard";

    /** Default round cap for a runtime-override guard. */
    static final int RUNTIME_MAX_ROUNDS = 3;

    /** Wall-clock for a guard-script run. */
    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(30);

    /** Supervisor tool surface: the guard may spawn, but not exec/write files. */
    private static final Set<String> SUPERVISOR_TOOLS = Set.of("process_spawn");

    /** Cap on tracked processes / sessions in the transient scratch stores. */
    private static final int SCRATCH_MAX = 10_000;

    private static final String METRIC = "vance.guard.evaluations";

    /**
     * Re-entrancy marker: {@code true} while a guard script runs on this
     * thread (or an inheriting child thread — GraalJS watchdog evals).
     * The COMMAND gate skips commands fired from within a guard run: a
     * guard does not judge its own actions. See {@code planning/shooty.md} §1.2.
     */
    private static final InheritableThreadLocal<Boolean> IN_GUARD_RUN = new InheritableThreadLocal<>();

    /** Whether the current thread executes inside a guard script run. */
    public static boolean inGuardRun() {
        return Boolean.TRUE.equals(IN_GUARD_RUN.get());
    }

    private final RecipeResolver recipeResolver;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ProcessEventEmitter eventEmitter;
    private final ScriptExecutor scriptExecutor;
    private final DocumentService documentService;
    private final DocumentRefResolver refResolver;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final ToolDispatcher toolDispatcher;
    private final ProgressEmitter progressEmitter;
    private final NotificationService notificationService;
    private final SessionService sessionService;
    // Lazy — breaks the cycle ShootyGuardService → ThinkEngineService →
    // engines → ShootyGuardService. Only touched for allowTools guards.
    private final ObjectProvider<ThinkEngineService> thinkEngineProvider;
    // Lazy — breaks the cycle ShootyGuardService → SkillSteerProcessor →
    // SkillCommandRunner → EngineCommandDispatcher → ShootyGuardService.
    // Only touched for vance.guard.activateSkill calls.
    private final ObjectProvider<SkillSteerProcessor> skillSteerProvider;
    private final MetricService metrics;

    /** Transient per-loop scratch: processId → flags. Bounded LRU, non-persistent. */
    private final Map<String, Map<String, Object>> loopScratch = boundedLru(SCRATCH_MAX);
    /** Transient per-session scratch: sessionId → flags. Bounded LRU, non-persistent. */
    private final Map<String, Map<String, Object>> sessionScratch = boundedLru(SCRATCH_MAX);
    /**
     * Transient turn-prompt replacement: processId → this turn's system
     * prompt, set by a START guard via {@code vance.guard.setTurnPrompt}
     * and consumed by {@link GuardTurnContextHandler}. Bounded LRU,
     * non-persistent; cleared at the next genuine user turn.
     */
    private final Map<String, String> turnPromptStore = boundedLru(SCRATCH_MAX);

    // ────────────────────────── STOP / TERMINATE ──────────────────────────

    /**
     * Evaluates all applicable yield-point guards for {@code process}.
     * Runs each guard's script; the first script that injects a
     * follow-up (via {@code vance.guard.continueWith}) wins and returns
     * {@link GuardEvaluation#fired}. Remaining guards are re-checked on
     * the next completion. Fail-open on script errors.
     *
     * @param naturalStop {@code true} for a natural stop, {@code false}
     *                    for an explicit terminate — matched against each
     *                    guard's {@link GuardPoint}
     */
    public GuardEvaluation evaluate(ThinkProcessDocument process, @Nullable String finalOutput, boolean naturalStop) {
        List<GuardConfig> guards = resolveGuards(process);
        boolean any = false;
        boolean anyError = false;
        log.trace(
                "Guard evaluate id='{}' naturalStop={} guardRounds={} resolvedGuards={}",
                process.getId(),
                naturalStop,
                process.getGuardRounds(),
                guards.size());
        for (GuardConfig guard : guards) {
            boolean triggerMatch = naturalStop
                    ? guard.trigger().firesOnNaturalStop()
                    : guard.trigger().firesOnTerminate();
            if (!triggerMatch) {
                log.trace(
                        "Guard id='{}' skip — trigger={} does not match naturalStop={}",
                        process.getId(),
                        guard.trigger(),
                        naturalStop);
                continue;
            }
            if (process.getGuardRounds() >= guard.maxRounds()) {
                log.trace(
                        "Guard id='{}' skip — round-cap reached ({} >= {})",
                        process.getId(),
                        process.getGuardRounds(),
                        guard.maxRounds());
                continue;
            }
            any = true;
            GuardEvaluation fired;
            try {
                fired = runStopGuard(process, guard, finalOutput, naturalStop);
            } catch (GuardScriptFailure e) {
                anyError = true;
                log.warn(
                        "Guard id='{}' script failed ({}) — fail-open: {}",
                        process.getId(),
                        e.failureClass(),
                        e.getMessage());
                metrics.counter(METRIC, "outcome", "script_error").increment();
                continue;
            }
            if (fired != null) {
                return fired;
            }
        }
        // "passed" says every applicable guard actually passed — a script
        // error already counted as script_error and is not a pass (fail-open
        // means the engine proceeds, not that the guard agreed).
        if (any && !anyError) {
            log.trace("Guard evaluate id='{}' — all applicable guards passed", process.getId());
            metrics.counter(METRIC, "outcome", "passed").increment();
        }
        return GuardEvaluation.passed();
    }

    /**
     * One yield-point guard's run. Returns a {@link GuardEvaluation#fired}
     * when the script injected a follow-up, else {@code null} (script
     * passed, was not found, or failed — fail-open in all three cases).
     * Throws {@link GuardScriptFailure} on a script error; the per-point
     * fail strategy (here: open) is decided by the caller.
     */
    private @Nullable GuardEvaluation runStopGuard(
            ThinkProcessDocument process, GuardConfig guard, @Nullable String finalOutput, boolean naturalStop)
            throws GuardScriptFailure {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>(null);
        int[] localRounds = {process.getGuardRounds()};
        GuardScriptHost host = stopHost(process, guard, fired, reason, localRounds);
        runGuardScript(process, guard, naturalStop ? "stop" : "terminate", null, finalOutput, naturalStop, null, host);
        if (fired.get()) {
            metrics.counter(METRIC, "outcome", "fired").increment();
            return GuardEvaluation.fired(guard, reason.get());
        }
        return null;
    }

    /**
     * The yield-point host: cap-aware {@code continueWith} (existing
     * v2 behavior — persistent {@code guardRounds} increment, pending-queue
     * injection, lane-turn schedule), {@code deny} unavailable, skill
     * activation allowed.
     */
    private GuardScriptHost stopHost(
            ThinkProcessDocument process,
            GuardConfig guard,
            AtomicBoolean fired,
            AtomicReference<String> reason,
            int[] localRounds) {
        return new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                if (localRounds[0] >= guard.maxRounds()) {
                    log.trace(
                            "Guard id='{}' continueWith refused — cap reached ({} >= {})",
                            process.getId(),
                            localRounds[0],
                            guard.maxRounds());
                    return false;
                }
                int nr = thinkProcessService.incrementGuardRounds(process.getId());
                localRounds[0] = nr >= 0 ? nr : localRounds[0] + 1;
                inject(process, prompt);
                eventEmitter.scheduleTurn(process.getId());
                fired.set(true);
                reason.compareAndSet(null, prompt);
                log.info(
                        "Guard fired id='{}' round={} prompt='{}'",
                        process.getId(),
                        localRounds[0],
                        abbreviate(prompt));
                return true;
            }

            @Override
            public boolean deny(String reasonForDeny) {
                throw unavailable("deny", "stop/terminate");
            }

            @Override
            public boolean activateSkill(String skillName, @Nullable String args) {
                return ShootyGuardService.this.activateSkill(process, skillName, args);
            }

            @Override
            public void setTurnPrompt(String text) {
                throw unavailable("setTurnPrompt", "stop/terminate");
            }
        };
    }

    // ───────────────────────────── START ─────────────────────────────

    /**
     * The START point: runs once per genuine user turn — call at turn
     * start, <b>after</b> {@link #resetIfUserTurn} (so the script starts
     * on a clean budget and clean loop scratch). Guard scripts with
     * {@code trigger: start} see the turn's user input as
     * {@code vance.guard.task} and decide themselves what to do (typical:
     * {@code vance.guard.activateSkill(...)}); per-process "already ran"
     * flags belong in {@code sessionValues}, which survives the reset.
     * Fail-open: a script error never blocks the turn.
     *
     * <p>Guard-injected turns (sender {@code _guard}) are not genuine
     * user turns and fire nothing — otherwise every completion-guard
     * round would multiply start-guard runs.
     *
     * <p>Engines do not call this (and {@link #resetIfUserTurn}) directly —
     * they call {@link #guardsOnTurnStart}, which owns the mandatory
     * ordering (reset first, then start guards) in one place.
     */
    public void runStartGuards(ThinkProcessDocument process, List<SteerMessage> inbox) {
        if (inbox == null || inbox.isEmpty()) {
            return;
        }
        String userText = genuineUserInput(inbox);
        if (userText == null) {
            return;
        }
        // A genuine user turn starts a fresh work unit: a turn prompt the
        // previous turn's START guard may have set is stale now. Clear it
        // before the guards run — by default nothing is replaced.
        turnPromptStore.remove(process.getId());
        boolean any = false;
        boolean anyError = false;
        for (GuardConfig guard : resolveGuards(process)) {
            if (!guard.trigger().firesOnStart()) {
                continue;
            }
            any = true;
            GuardScriptHost host = new GuardScriptHost() {
                @Override
                public boolean continueWith(String prompt) {
                    throw unavailable("continueWith", "start");
                }

                @Override
                public boolean deny(String reason) {
                    throw unavailable("deny", "start");
                }

                @Override
                public boolean activateSkill(String skillName, @Nullable String args) {
                    return ShootyGuardService.this.activateSkill(process, skillName, args);
                }

                @Override
                public void setTurnPrompt(String text) {
                    turnPromptStore.put(process.getId(), text);
                    log.info("Guard set turn prompt id='{}' ({} chars)", process.getId(), text.length());
                }
            };
            try {
                runGuardScript(process, guard, "start", userText, null, /*naturalStop*/ true, null, host);
            } catch (GuardScriptFailure e) {
                anyError = true;
                log.warn(
                        "Guard id='{}' start script failed ({}) — fail-open: {}",
                        process.getId(),
                        e.failureClass(),
                        e.getMessage());
                metrics.counter(METRIC, "outcome", "script_error").increment();
            }
        }
        // "passed" says every applicable guard actually passed — a script
        // error already counted as script_error and is not a pass (fail-open
        // means the turn proceeds, not that the guard agreed).
        if (any && !anyError) {
            log.trace("Guard start id='{}' — all applicable start guards passed", process.getId());
            metrics.counter(METRIC, "outcome", "passed").increment();
        }
    }

    // ───────────────────────────── COMMAND ─────────────────────────────

    /**
     * The combined turn-start anchor: resets the per-process guard budget
     * and loop scratch when {@code inbox} carries genuine user input, then
     * runs the START guards — in exactly this order, per
     * {@code specification/public/shooty.md} §2.2 ("erst Budget-Reset +
     * Loop-Scratch-Wipe, dann Start-Guards" — das Skript startet auf
     * sauberer Tafel). Engines call this instead of the two steps
     * separately so the ordering cannot drift per engine.
     */
    public void guardsOnTurnStart(ThinkProcessDocument process, List<SteerMessage> inbox) {
        resetIfUserTurn(process, inbox);
        runStartGuards(process, inbox);
    }

    /**
     * The COMMAND point: gates {@code command} before its handler runs.
     * Returns {@code null} when the command may proceed; a non-null
     * result is a hard, fail-closed denial
     * ({@link EngineCommandResult#guardDenied}).
     *
     * <p>Fail-closed in every failure mode: a guard script that denies
     * (via {@code vance.guard.deny(reason)}), errors, times out, or is
     * not found fails the command. "The guard never blocks" holds only
     * for the START/STOP points — here, blocking is the job.
     *
     * <p>Re-entrancy: skipped when {@link #inGuardRun()} — a guard does
     * not judge its own actions (commands fired by skill activation,
     * LLM calls, future hooks). The dispatcher checks the marker too,
     * before it even calls this method — kept deliberately: the check here
     * also guards future call sites of this public method.
     */
    public @Nullable EngineCommandResult gateCommand(ThinkProcessDocument process, EngineCommand command) {
        if (inGuardRun()) {
            return null;
        }
        boolean any = false;
        for (GuardConfig guard : resolveGuards(process)) {
            if (!guard.trigger().firesOnCommand()) {
                continue;
            }
            any = true;
            AtomicReference<String> denied = new AtomicReference<>(null);
            GuardScriptHost host = new GuardScriptHost() {
                @Override
                public boolean continueWith(String prompt) {
                    throw unavailable("continueWith", "command");
                }

                @Override
                public boolean deny(String reason) {
                    denied.compareAndSet(null, reason);
                    log.info(
                            "Guard denied command id='{}' verb='{}' reason='{}'",
                            process.getId(),
                            command.name(),
                            reason);
                    return true;
                }

                @Override
                public boolean activateSkill(String skillName, @Nullable String args) {
                    return ShootyGuardService.this.activateSkill(process, skillName, args);
                }

                @Override
                public void setTurnPrompt(String text) {
                    throw unavailable("setTurnPrompt", "command");
                }
            };
            try {
                runGuardScript(
                        process, guard, "command", null, null, /*naturalStop*/ false, commandContext(command), host);
            } catch (GuardScriptFailure e) {
                log.warn(
                        "Guard id='{}' command script failed (verb='{}', {}) " + "— fail-closed, command denied: {}",
                        process.getId(),
                        command.name(),
                        e.failureClass(),
                        e.getMessage());
                metrics.counter(METRIC, "outcome", "script_error").increment();
                return EngineCommandResult.guardDenied("Guard script failed (fail-closed): " + e.getMessage());
            }
            String reason = denied.get();
            if (reason != null) {
                metrics.counter(METRIC, "outcome", "denied").increment();
                return EngineCommandResult.guardDenied("Guard denied: " + reason);
            }
        }
        if (any) {
            log.trace(
                    "Guard command id='{}' verb='{}' — all applicable guards passed", process.getId(), command.name());
            metrics.counter(METRIC, "outcome", "passed").increment();
        }
        return null;
    }

    /**
     * The turn-prompt replacement a START guard set for this process's
     * current turn, or {@code null} when the prompt is not manipulated
     * (the default). Read by {@link GuardTurnContextHandler} before each
     * LLM request.
     */
    public @Nullable String turnPromptFor(ThinkProcessDocument process) {
        return turnPromptStore.get(process.getId());
    }

    /** The {@code vance.guard.command} context: {@code {name, args}}. */
    private static Map<String, Object> commandContext(EngineCommand command) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("name", command.name());
        ctx.put("args", command.args());
        return ctx;
    }

    // ───────────────────────── Shared script run ─────────────────────────

    /**
     * A guard script failure, reported to the per-point caller which
     * decides the fail strategy (open or closed).
     */
    private static final class GuardScriptFailure extends RuntimeException {

        private final String failureClass;

        GuardScriptFailure(@Nullable String failureClass, @Nullable String message) {
            super(message);
            this.failureClass = failureClass == null ? "unknown" : failureClass;
        }

        GuardScriptFailure(@Nullable String failureClass, @Nullable String message, @Nullable Throwable cause) {
            super(message, cause);
            this.failureClass = failureClass == null ? "unknown" : failureClass;
        }

        String failureClass() {
            return failureClass;
        }
    }

    /**
     * Runs one guard's script at {@code pointName} with the given host.
     * Sets the re-entrancy marker for the duration of the run (the host
     * actions and the script's own tool/LLM calls execute inside it).
     * Throws {@link GuardScriptFailure} on any script error; a blank or
     * missing script is a failure too — fail strategies differ per point,
     * so the error is reported, not swallowed.
     *
     * @param task the {@code vance.guard.task} override — the turn's
     *             genuine user input at the start point; {@code null}
     *             falls back to the process's first user message
     */
    private void runGuardScript(
            ThinkProcessDocument process,
            GuardConfig guard,
            String pointName,
            @Nullable String task,
            @Nullable String finalOutput,
            boolean naturalStop,
            @Nullable Map<String, Object> commandContext,
            GuardScriptHost host)
            throws GuardScriptFailure {
        String code = loadScript(process, guard);
        if (StringUtils.isBlank(code)) {
            throw new GuardScriptFailure(
                    "script_missing", "script not found/empty (path='" + guard.scriptPath() + "')");
        }

        ScriptGuardApi guardApi = new ScriptGuardApi(
                task == null ? firstUserInput(process) : task,
                finalOutput == null ? "" : finalOutput,
                process.getGuardRounds(),
                guard.maxRounds(),
                naturalStop,
                pointName,
                commandContext,
                new ScriptGuardScratchApi(loopStore(process)),
                new ScriptGuardScratchApi(sessionStore(process)),
                host);

        ScriptRequest request = new ScriptRequest(
                        "js",
                        code,
                        sourceName(process, guard),
                        buildToolSurface(process, guard.allowTools()),
                        SCRIPT_TIMEOUT,
                        Map.of("args", guard.params() == null ? Map.of() : guard.params()),
                        null,
                        ScopeLevel.PROCESS_SCOPED,
                        progressBridge(process),
                        notificationBridge(process))
                .withGuardApi(guardApi);

        IN_GUARD_RUN.set(true);
        try {
            scriptExecutor.run(request);
        } catch (ScriptExecutionException e) {
            throw new GuardScriptFailure(e.errorClass().name(), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new GuardScriptFailure(e.getClass().getSimpleName(), e.toString(), e);
        } finally {
            IN_GUARD_RUN.remove();
        }
    }

    /**
     * Host action backing {@code vance.guard.activateSkill} — sticky,
     * auto-trigger-style (no separate action turn; the enclosing or
     * upcoming turn covers the work). Runs with the re-entrancy marker
     * set, so the skill's activate-command sequence bypasses the
     * COMMAND gate.
     */
    private boolean activateSkill(ThinkProcessDocument process, String skillName, @Nullable String args) {
        SkillSteerProcessor skills = skillSteerProvider.getIfAvailable();
        if (skills == null) {
            throw new ScriptHostException("vance.guard.activateSkill: skill subsystem unavailable", null);
        }
        return skills.activate(process, skillName, /*oneShot*/ false, /*runAction*/ false, args, sessionOwner(process))
                .newlyActivated();
    }

    private static RuntimeException unavailable(String op, String point) {
        return new ScriptHostException("vance.guard." + op + ": not available at point '" + point + "'", null);
    }

    // ──────────────────────── User-turn reset ────────────────────────

    /**
     * Resets the per-process guard round budget and clears the loop
     * scratch when {@code inbox} carries genuine (non-guard-injected)
     * user input. A single-action chat engine (Arthur, Eddie) is
     * long-lived, so without this the lifetime round counter would climb
     * across user turns and permanently disable the guard after
     * {@code maxRounds} fires. Each fresh user request restarts the "are
     * you really done?" negotiation with a full budget and a clean
     * "already asked" slate — and the START guards run right after this,
     * on that clean slate.
     */
    public void resetIfUserTurn(ThinkProcessDocument process, List<SteerMessage> inbox) {
        if (inbox == null) {
            return;
        }
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null
                    && !uci.content().isBlank()
                    && !INJECT_SENDER.equals(uci.fromUser())) {
                if (process.getGuardRounds() > 0) {
                    thinkProcessService.resetGuardRounds(process.getId());
                }
                loopScratch.remove(process.getId());
                log.trace("Guard rounds + loop scratch reset id='{}' — genuine user turn", process.getId());
                return;
            }
        }
    }

    /**
     * The first genuine (non-guard-injected) user input of an inbox, or
     * {@code null} — the shared "is this a real user turn" detection of
     * {@link #resetIfUserTurn} and {@link #runStartGuards}.
     */
    private static @Nullable String genuineUserInput(List<SteerMessage> inbox) {
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null
                    && !uci.content().isBlank()
                    && !INJECT_SENDER.equals(uci.fromUser())) {
                return uci.content();
            }
        }
        return null;
    }

    /**
     * The effective guards for a process: recipe {@code guard:} block plus
     * the additive runtime override ({@code guardScriptOverride}, a script
     * path). Also used by the {@code guard} command's {@code get}.
     */
    public List<GuardConfig> resolveGuards(ThinkProcessDocument process) {
        List<GuardConfig> out = new ArrayList<>();
        String recipeName = process.getRecipeName();
        if (recipeName != null && !recipeName.isBlank()) {
            try {
                recipeResolver
                        .resolve(process.getTenantId(), process.getProjectId(), recipeName)
                        .ifPresent(recipe -> out.addAll(recipe.guards()));
            } catch (RuntimeException e) {
                log.warn("Guard id='{}' recipe='{}' resolve failed: {}", process.getId(), recipeName, e.toString());
            }
        }
        int recipeGuards = out.size();
        String bodyOverride = process.getGuardScriptBodyOverride();
        String pathOverride = process.getGuardScriptOverride();
        boolean runtimeActive = true;
        if (StringUtils.isNotBlank(bodyOverride)) {
            out.add(GuardConfig.scriptBody(bodyOverride, false, GuardPoint.STOP, RUNTIME_MAX_ROUNDS));
        } else if (StringUtils.isNotBlank(pathOverride)) {
            out.add(GuardConfig.scriptPath(pathOverride, false, GuardPoint.STOP, RUNTIME_MAX_ROUNDS));
        } else {
            runtimeActive = false;
        }
        log.trace(
                "Guard resolveGuards id='{}' recipe='{}' recipeGuards={} runtimeOverride={}",
                process.getId(),
                process.getRecipeName(),
                recipeGuards,
                runtimeActive);
        return out;
    }

    // ──────────── Runtime scratch inspection (//guard status) ────────────

    /** Snapshot of a process's loop scratch (empty if none yet). */
    public Map<String, Object> loopScratchView(ThinkProcessDocument process) {
        Map<String, Object> m = loopScratch.get(process.getId());
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    /**
     * Snapshot of a process's session scratch (empty if none yet). For a
     * session-less process this is its loop scratch — the same store
     * {@link #sessionStore} hands the script, so what {@code //guard
     * status session} shows is what the script sees.
     */
    public Map<String, Object> sessionScratchView(ThinkProcessDocument process) {
        Map<String, Object> m = existingSessionStore(process);
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    /**
     * Sets a scratch value from the runtime command. Stored into the same
     * backing a guard script reads, so the value is visible to the script
     * (as a String — command args are untyped; scripts use truthy checks).
     */
    public void putScratch(ThinkProcessDocument process, boolean session, String key, String value) {
        (session ? sessionStore(process) : loopStore(process)).put(key, value);
    }

    /** Removes a scratch key; returns {@code true} if it was present. */
    public boolean removeScratch(ThinkProcessDocument process, boolean session, String key) {
        Map<String, Object> m = session ? existingSessionStore(process) : loopScratch.get(process.getId());
        return m != null && m.remove(key) != null;
    }

    /** Clears a whole scratch scope. */
    public void clearScratch(ThinkProcessDocument process, boolean session) {
        if (session) {
            String sessionId = sessionKey(process);
            if (sessionId != null) {
                sessionScratch.remove(sessionId);
            } else {
                // Session-less: the session scope *is* the loop scope here
                // (see sessionStore). Dropping the entry would leave the
                // script's loopValues intact, so clear in place instead.
                Map<String, Object> m = loopScratch.get(process.getId());
                if (m != null) {
                    m.clear();
                }
            }
        } else {
            loopScratch.remove(process.getId());
        }
    }

    /**
     * The session scratch as it exists right now, or {@code null} when
     * nothing was ever written. Resolves the session-less fallback the
     * same way {@link #sessionStore} does, so the inspect/remove paths
     * cannot disagree with the read/write path about which map is "the
     * session scratch" — they used to, and {@code //guard status session
     * del} then reported "not present" for a key the script could read.
     */
    private @Nullable Map<String, Object> existingSessionStore(ThinkProcessDocument process) {
        String sessionId = sessionKey(process);
        return sessionId == null ? loopScratch.get(process.getId()) : sessionScratch.get(sessionId);
    }

    /**
     * Whether {@code process} owns a session — the one blank-aware
     * predicate every caller must use. A session-less process carries
     * {@code null} (Lombok builder without {@code @Builder.Default}) or
     * {@code ""} (field initializer / persisted documents) — testing for
     * {@code null} alone misclassifies the {@code ""} shape as sessioned.
     */
    public static boolean hasSession(ThinkProcessDocument process) {
        return sessionKey(process) != null;
    }

    /**
     * The process's session id, or {@code null} when it has none.
     *
     * <p>{@code ThinkProcessDocument.sessionId} is {@code @NullMarked} and
     * defaults to the empty string, so a session-less process carries
     * {@code ""} rather than {@code null}. Testing for {@code null} alone
     * would key every such process on the same {@code ""} entry — one
     * session scratch shared by every headless worker on the pod.
     */
    private static @Nullable String sessionKey(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        return sessionId == null || sessionId.isBlank() ? null : sessionId;
    }

    // ──────────────────── Helpers ────────────────────

    private @Nullable String loadScript(ThinkProcessDocument process, GuardConfig guard) {
        if (guard.scriptPath() != null) {
            DocumentRef ref;
            try {
                // Guard-script refs are authored project-relative (recipe /
                // runtime override), so the referrer base is the project root.
                // Supports /absolute and //other-project/… cross-project refs.
                ref = refResolver.resolve(guard.scriptPath(), DocumentRefContext.root(process.getProjectId()));
            } catch (DocumentRefException e) {
                log.warn("Guard id='{}' bad script ref '{}': {}", process.getId(), guard.scriptPath(), e.getMessage());
                return null;
            }
            // A cross-project ref (//other-project/…) resolves to a real
            // path in another project, and DocumentRefResolver is pure
            // computation by contract — the READ check belongs here, at
            // the call site. Only enforced when the ref actually leaves
            // the process's own project: an in-project guard script is
            // covered by the EXECUTE the caller already needed to install
            // it, and checking it would cost a permission round-trip on
            // every yield point.
            if (!process.getProjectId().equals(ref.projectId())) {
                String owner = sessionOwner(process);
                if (owner == null) {
                    // No session, so no identity to check against — and
                    // forToolSubject would map that to SecurityContext.SYSTEM,
                    // which passes every enforce. A session-less process (a
                    // headless worker, a scheduler-spawned run) is exactly the
                    // case where nobody vouched for the ref, so refuse rather
                    // than let the check quietly become a no-op. An in-project
                    // script still loads; only leaving the project needs an owner.
                    log.warn(
                            "Guard id='{}' has no session owner — refusing the "
                                    + "cross-project script '{}' in project '{}'",
                            process.getId(),
                            ref.path(),
                            ref.projectId());
                    return null;
                }
                try {
                    permissionService.enforce(
                            contextFactory.forToolSubject(process.getTenantId(), owner),
                            new Resource.Document(process.getTenantId(), ref.projectId(), ref.path()),
                            Action.READ);
                } catch (RuntimeException denied) {
                    log.warn(
                            "Guard id='{}' may not read cross-project script " + "'{}' in project '{}': {}",
                            process.getId(),
                            ref.path(),
                            ref.projectId(),
                            denied.getMessage());
                    return null;
                }
            }
            return documentService
                    .lookupCascade(process.getTenantId(), ref.projectId(), ref.path())
                    .map(hit -> hit.content())
                    .orElse(null);
        }
        return guard.scriptBody();
    }

    private ContextToolsApi buildToolSurface(ThinkProcessDocument process, boolean allowTools) {
        if (allowTools) {
            ThinkEngineService engines = thinkEngineProvider.getIfAvailable();
            if (engines != null) {
                try {
                    return engines.newContext(process).tools();
                } catch (RuntimeException e) {
                    log.warn(
                            "Guard id='{}' full tool surface resolve failed — "
                                    + "falling back to supervisor surface: {}",
                            process.getId(),
                            e.toString());
                }
            }
        }
        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                sessionOwner(process));
        return new ContextToolsApi(toolDispatcher, scope, SUPERVISOR_TOOLS);
    }

    private @Nullable String sessionOwner(ThinkProcessDocument process) {
        String sessionId = sessionKey(process);
        if (sessionId == null) {
            return null;
        }
        return sessionService
                .findBySessionId(sessionId)
                .map(SessionDocument::getUserId)
                .orElse(null);
    }

    private BiConsumer<String, @Nullable Map<String, Object>> progressBridge(ThinkProcessDocument process) {
        return (message, payload) -> {
            StatusPayload.StatusPayloadBuilder builder =
                    StatusPayload.builder().tag(StatusTag.SCRIPT_PROGRESS).text(message);
            if (payload != null && !payload.isEmpty()) {
                builder.detail(formatPayload(payload));
            }
            progressEmitter.emitStatus(process, builder.build());
        };
    }

    private BiConsumer<String, @Nullable NotificationSeverity> notificationBridge(ThinkProcessDocument process) {
        return (message, severity) -> notificationService.publish(process, message, severity);
    }

    private Map<String, Object> loopStore(ThinkProcessDocument process) {
        return loopScratch.computeIfAbsent(process.getId(), k -> new ConcurrentHashMap<>());
    }

    /**
     * The session scratch, or — for a session-less process (a headless
     * worker, a scheduler-spawned run) — its loop scratch. Falling back
     * rather than handing out a throw-away map matters: the script's
     * {@code sessionValues.set(...)} used to succeed and then vanish, so
     * an "already asked" flag never took and the guard re-asked forever.
     * Loop scope is the narrower store, so the fallback can only
     * under-remember, never leak across processes.
     */
    private Map<String, Object> sessionStore(ThinkProcessDocument process) {
        String sessionId = sessionKey(process);
        if (sessionId == null) {
            return loopStore(process);
        }
        return sessionScratch.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
    }

    private void inject(ThinkProcessDocument process, String prompt) {
        // The "[completion-guard] " prefix is the stable v2 wire marker —
        // deliberately kept under the Shooty rename: history entries and
        // scripts may match on it, and the sender id (`_guard`), not the
        // prefix, carries the machine-readable semantics.
        String content = "[completion-guard] " + prompt;
        SteerMessage.UserChatInput injected =
                new SteerMessage.UserChatInput(Instant.now(), null, INJECT_SENDER, content);
        thinkProcessService.appendPending(process.getId(), SteerMessageCodec.toDocument(injected));
    }

    private String firstUserInput(ThinkProcessDocument process) {
        try {
            List<ChatMessageDocument> history =
                    chatMessageService.activeHistory(process.getTenantId(), process.getSessionId(), process.getId());
            for (ChatMessageDocument m : history) {
                if (m.getRole() == ChatRole.USER
                        && m.getContent() != null
                        && !m.getContent().isBlank()) {
                    return m.getContent();
                }
            }
        } catch (RuntimeException e) {
            log.debug("Guard firstUserInput lookup failed id='{}': {}", process.getId(), e.toString());
        }
        String goal = process.getGoal();
        return goal == null ? "" : goal;
    }

    private static String sourceName(ThinkProcessDocument process, GuardConfig guard) {
        return "guard:" + (guard.scriptPath() != null ? guard.scriptPath() : process.getId());
    }

    private static String formatPayload(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String abbreviate(@Nullable String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private static <V> Map<String, V> boundedLru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }
}
