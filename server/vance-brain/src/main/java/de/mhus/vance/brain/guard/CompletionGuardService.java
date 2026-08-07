package de.mhus.vance.brain.guard;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.recipe.GuardConfig;
import de.mhus.vance.brain.recipe.GuardTrigger;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.script.GuardScriptHost;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardScratchApi;
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
 * Engine-agnostic completion guard: at an engine's yield point (Frankie
 * stop-paths, Arthur/Eddie post-reply, …) it runs each configured guard's
 * <b>JS script</b> via the shared {@link ScriptExecutor}. The script
 * decides judge + action imperatively through the {@code vance.guard.*}
 * surface — {@code vance.guard.continueWith(prompt)} injects a follow-up
 * into the process's own pending queue and schedules a lane turn, so the
 * process keeps working instead of yielding.
 *
 * <p>Guards come from the recipe {@code guard:} block plus an additive
 * per-process runtime override ({@code guardScriptOverride}). Backstops:
 * a per-guard {@code maxRounds} cap against the process's persistent
 * {@code guardRounds} counter (enforced by the cap-aware
 * {@link GuardScriptHost}), and fail-open on any script error (never
 * blocks the engine). Transient per-loop / per-session scratch stores
 * ({@code vance.guard.loopValues} / {@code sessionValues}) let a script
 * remember "already asked X" across the re-entrant runs.
 *
 * <p>See {@code planning/completion-guard.md} v2.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompletionGuardService {

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
    // Lazy — breaks the cycle CompletionGuardService → ThinkEngineService →
    // engines → CompletionGuardService. Only touched for allowTools guards.
    private final ObjectProvider<ThinkEngineService> thinkEngineProvider;
    private final MetricService metrics;

    /** Transient per-loop scratch: processId → flags. Bounded LRU, non-persistent. */
    private final Map<String, Map<String, Object>> loopScratch = boundedLru(SCRATCH_MAX);
    /** Transient per-session scratch: sessionId → flags. Bounded LRU, non-persistent. */
    private final Map<String, Map<String, Object>> sessionScratch = boundedLru(SCRATCH_MAX);

    /**
     * Evaluates all applicable guards for {@code process} at a yield
     * point. Runs each guard's script; the first script that injects a
     * follow-up (via {@code vance.guard.continueWith}) wins and returns
     * {@link GuardEvaluation#fired}. Remaining guards are re-checked on
     * the next completion.
     *
     * @param naturalStop {@code true} for a natural stop, {@code false}
     *                    for an explicit terminate — matched against each
     *                    guard's {@link GuardTrigger}
     */
    public GuardEvaluation evaluate(
            ThinkProcessDocument process, @Nullable String finalOutput, boolean naturalStop) {
        List<GuardConfig> guards = resolveGuards(process);
        log.trace("Guard evaluate id='{}' naturalStop={} guardRounds={} resolvedGuards={}",
                process.getId(), naturalStop, process.getGuardRounds(), guards.size());
        if (guards.isEmpty()) {
            return GuardEvaluation.noop();
        }
        for (GuardConfig guard : guards) {
            boolean triggerMatch = naturalStop
                    ? guard.trigger().firesOnNaturalStop()
                    : guard.trigger().firesOnTerminate();
            if (!triggerMatch) {
                log.trace("Guard id='{}' skip — trigger={} does not match naturalStop={}",
                        process.getId(), guard.trigger(), naturalStop);
                continue;
            }
            if (process.getGuardRounds() >= guard.maxRounds()) {
                log.trace("Guard id='{}' skip — round-cap reached ({} >= {})",
                        process.getId(), process.getGuardRounds(), guard.maxRounds());
                continue;
            }
            GuardEvaluation fired = runGuardScript(process, guard, finalOutput, naturalStop);
            if (fired != null) {
                return fired;
            }
        }
        log.trace("Guard evaluate id='{}' — all applicable guards passed", process.getId());
        metrics.counter(METRIC, "outcome", "passed").increment();
        return GuardEvaluation.passed();
    }

    /**
     * Runs one guard's script. Returns a {@link GuardEvaluation#fired}
     * when the script injected a follow-up, else {@code null} (script
     * passed, was not found, or failed — fail-open in all three cases).
     */
    private @Nullable GuardEvaluation runGuardScript(
            ThinkProcessDocument process, GuardConfig guard,
            @Nullable String finalOutput, boolean naturalStop) {
        String code = loadScript(process, guard);
        if (StringUtils.isBlank(code)) {
            log.warn("Completion guard id='{}' script not found/empty (path='{}') — skipping",
                    process.getId(), guard.scriptPath());
            metrics.counter(METRIC, "outcome", "script_error").increment();
            return null;
        }

        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>(null);
        int[] localRounds = { process.getGuardRounds() };
        GuardScriptHost host = prompt -> {
            if (localRounds[0] >= guard.maxRounds()) {
                log.trace("Guard id='{}' continueWith refused — cap reached ({} >= {})",
                        process.getId(), localRounds[0], guard.maxRounds());
                return false;
            }
            int nr = thinkProcessService.incrementGuardRounds(process.getId());
            localRounds[0] = nr >= 0 ? nr : localRounds[0] + 1;
            inject(process, prompt);
            eventEmitter.scheduleTurn(process.getId());
            fired.set(true);
            reason.compareAndSet(null, prompt);
            log.info("Completion guard fired id='{}' round={} prompt='{}'",
                    process.getId(), localRounds[0], abbreviate(prompt));
            return true;
        };

        ScriptGuardApi guardApi = new ScriptGuardApi(
                firstUserInput(process),
                finalOutput == null ? "" : finalOutput,
                process.getGuardRounds(),
                guard.maxRounds(),
                naturalStop,
                new ScriptGuardScratchApi(loopStore(process)),
                new ScriptGuardScratchApi(sessionStore(process)),
                host);

        ScriptRequest request = new ScriptRequest(
                "js", code, sourceName(process, guard),
                buildToolSurface(process, guard.allowTools()),
                SCRIPT_TIMEOUT,
                Map.of("args", guard.params() == null ? Map.of() : guard.params()),
                null, ScopeLevel.PROCESS_SCOPED,
                progressBridge(process), notificationBridge(process))
                .withGuardApi(guardApi);

        try {
            scriptExecutor.run(request);
        } catch (ScriptExecutionException e) {
            log.warn("Completion guard id='{}' script failed ({}) — fail-open: {}",
                    process.getId(), e.errorClass(), e.getMessage());
            metrics.counter(METRIC, "outcome", "script_error").increment();
        } catch (RuntimeException e) {
            log.warn("Completion guard id='{}' script raised — fail-open: {}",
                    process.getId(), e.toString());
            metrics.counter(METRIC, "outcome", "script_error").increment();
        }

        if (fired.get()) {
            metrics.counter(METRIC, "outcome", "fired").increment();
            return GuardEvaluation.fired(guard, reason.get());
        }
        return null;
    }

    /**
     * Resets the per-process guard round budget and clears the loop
     * scratch when {@code inbox} carries genuine (non-guard-injected)
     * user input. A single-action chat engine (Arthur, Eddie) is
     * long-lived, so without this the lifetime round counter would climb
     * across user turns and permanently disable the guard after
     * {@code maxRounds} fires. Each fresh user request restarts the "are
     * you really done?" negotiation with a full budget and a clean
     * "already asked" slate.
     */
    public void resetIfUserTurn(ThinkProcessDocument process, List<SteerMessage> inbox) {
        if (inbox == null) {
            return;
        }
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null && !uci.content().isBlank()
                    && !INJECT_SENDER.equals(uci.fromUser())) {
                if (process.getGuardRounds() > 0) {
                    thinkProcessService.resetGuardRounds(process.getId());
                }
                loopScratch.remove(process.getId());
                log.trace("Guard rounds + loop scratch reset id='{}' — genuine user turn",
                        process.getId());
                return;
            }
        }
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
                recipeResolver.resolve(process.getTenantId(), process.getProjectId(), recipeName)
                        .ifPresent(recipe -> out.addAll(recipe.guards()));
            } catch (RuntimeException e) {
                log.warn("Completion guard id='{}' recipe='{}' resolve failed: {}",
                        process.getId(), recipeName, e.toString());
            }
        }
        int recipeGuards = out.size();
        String bodyOverride = process.getGuardScriptBodyOverride();
        String pathOverride = process.getGuardScriptOverride();
        boolean runtimeActive = true;
        if (StringUtils.isNotBlank(bodyOverride)) {
            out.add(GuardConfig.scriptBody(
                    bodyOverride, false, GuardTrigger.STOP, RUNTIME_MAX_ROUNDS));
        } else if (StringUtils.isNotBlank(pathOverride)) {
            out.add(GuardConfig.scriptPath(
                    pathOverride, false, GuardTrigger.STOP, RUNTIME_MAX_ROUNDS));
        } else {
            runtimeActive = false;
        }
        log.trace("Guard resolveGuards id='{}' recipe='{}' recipeGuards={} runtimeOverride={}",
                process.getId(), process.getRecipeName(), recipeGuards, runtimeActive);
        return out;
    }

    // ──────────── Runtime scratch inspection (//guard status) ────────────

    /** Snapshot of a process's loop scratch (empty if none yet). */
    public Map<String, Object> loopScratchView(ThinkProcessDocument process) {
        Map<String, Object> m = loopScratch.get(process.getId());
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    /** Snapshot of a process's session scratch (empty if none / no session). */
    public Map<String, Object> sessionScratchView(ThinkProcessDocument process) {
        String sid = process.getSessionId();
        Map<String, Object> m = sid == null ? null : sessionScratch.get(sid);
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
        Map<String, Object> m = session
                ? (process.getSessionId() == null ? null : sessionScratch.get(process.getSessionId()))
                : loopScratch.get(process.getId());
        return m != null && m.remove(key) != null;
    }

    /** Clears a whole scratch scope. */
    public void clearScratch(ThinkProcessDocument process, boolean session) {
        if (session) {
            if (process.getSessionId() != null) {
                sessionScratch.remove(process.getSessionId());
            }
        } else {
            loopScratch.remove(process.getId());
        }
    }

    // ──────────────────── Helpers ────────────────────

    private @Nullable String loadScript(ThinkProcessDocument process, GuardConfig guard) {
        if (guard.scriptPath() != null) {
            DocumentRef ref;
            try {
                // Guard-script refs are authored project-relative (recipe /
                // runtime override), so the referrer base is the project root.
                // Supports /absolute and //other-project/… cross-project refs.
                ref = refResolver.resolve(
                        guard.scriptPath(), DocumentRefContext.root(process.getProjectId()));
            } catch (DocumentRefException e) {
                log.warn("Completion guard id='{}' bad script ref '{}': {}",
                        process.getId(), guard.scriptPath(), e.getMessage());
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
                try {
                    permissionService.enforce(
                            contextFactory.forToolSubject(
                                    process.getTenantId(), sessionOwner(process)),
                            new Resource.Document(
                                    process.getTenantId(), ref.projectId(), ref.path()),
                            Action.READ);
                } catch (RuntimeException denied) {
                    log.warn("Completion guard id='{}' may not read cross-project script "
                                    + "'{}' in project '{}': {}",
                            process.getId(), ref.path(), ref.projectId(), denied.getMessage());
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
                    log.warn("Completion guard id='{}' full tool surface resolve failed — "
                            + "falling back to supervisor surface: {}", process.getId(), e.toString());
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
        if (process.getSessionId() == null) {
            return null;
        }
        return sessionService.findBySessionId(process.getSessionId())
                .map(SessionDocument::getUserId)
                .orElse(null);
    }

    private BiConsumer<String, @Nullable Map<String, Object>> progressBridge(
            ThinkProcessDocument process) {
        return (message, payload) -> {
            StatusPayload.StatusPayloadBuilder builder = StatusPayload.builder()
                    .tag(StatusTag.SCRIPT_PROGRESS)
                    .text(message);
            if (payload != null && !payload.isEmpty()) {
                builder.detail(formatPayload(payload));
            }
            progressEmitter.emitStatus(process, builder.build());
        };
    }

    private BiConsumer<String, @Nullable NotificationSeverity> notificationBridge(
            ThinkProcessDocument process) {
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
        String sessionId = process.getSessionId();
        if (sessionId == null) {
            return loopStore(process);
        }
        return sessionScratch.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
    }

    private void inject(ThinkProcessDocument process, String prompt) {
        String content = "[completion-guard] " + prompt;
        SteerMessage.UserChatInput injected = new SteerMessage.UserChatInput(
                Instant.now(), null, INJECT_SENDER, content);
        thinkProcessService.appendPending(
                process.getId(), SteerMessageCodec.toDocument(injected));
    }

    private String firstUserInput(ThinkProcessDocument process) {
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    process.getTenantId(), process.getSessionId(), process.getId());
            for (ChatMessageDocument m : history) {
                if (m.getRole() == ChatRole.USER
                        && m.getContent() != null && !m.getContent().isBlank()) {
                    return m.getContent();
                }
            }
        } catch (RuntimeException e) {
            log.debug("Completion guard firstUserInput lookup failed id='{}': {}",
                    process.getId(), e.toString());
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
