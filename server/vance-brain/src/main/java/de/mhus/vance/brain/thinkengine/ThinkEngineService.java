package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.progress.ProgressToolListener;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Think-engine registry and context factory.
 *
 * <p>Discovers every {@link ThinkEngine} Spring bean at startup and indexes
 * it by {@link ThinkEngine#name()}. Duplicate names fail fast.
 *
 * <p>Callers go through {@link #resolve(String)} to get the engine for a
 * given name and {@link #newContext(ThinkProcessDocument)} to build a fresh
 * {@link ThinkEngineContext} for a lifecycle call. The lane scheduler (not
 * here yet) will combine the two: take the process, resolve its engine,
 * build a context, invoke {@code start/resume/steer/...}.
 */
@Service
@Slf4j
public class ThinkEngineService {

    private final Map<String, ThinkEngine> engines;
    private final AiModelService aiModelService;
    private final SettingService settingService;
    private final ChatMessageService chatMessageService;
    private final ToolDispatcher toolDispatcher;
    private final ClientEventPublisher eventPublisher;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter processEventEmitter;
    private final ProgressToolListener progressToolListener;
    private final LlmTraceService llmTraceService;
    private final de.mhus.vance.brain.history.HistoryTagBuilder historyTagBuilder;
    private final de.mhus.vance.brain.tools.ToolResultStorage toolResultStorage;
    /** Lazy provider — RecipeResolver depends on us, so the bean graph cycles otherwise. */
    private final ObjectProvider<RecipeResolver> recipeResolverProvider;

    public ThinkEngineService(
            List<ThinkEngine> engineBeans,
            AiModelService aiModelService,
            SettingService settingService,
            ChatMessageService chatMessageService,
            ToolDispatcher toolDispatcher,
            ClientEventPublisher eventPublisher,
            SessionService sessionService,
            ThinkProcessService thinkProcessService,
            ProcessEventEmitter processEventEmitter,
            ProgressToolListener progressToolListener,
            LlmTraceService llmTraceService,
            de.mhus.vance.brain.history.HistoryTagBuilder historyTagBuilder,
            de.mhus.vance.brain.tools.ToolResultStorage toolResultStorage,
            ObjectProvider<RecipeResolver> recipeResolverProvider) {
        this.engines = engineBeans.stream().collect(
                Collectors.toMap(ThinkEngine::name, e -> e, (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate ThinkEngine name: " + a.name()
                                    + " — " + a.getClass() + " vs " + b.getClass());
                }));
        this.aiModelService = aiModelService;
        this.settingService = settingService;
        this.chatMessageService = chatMessageService;
        this.toolDispatcher = toolDispatcher;
        this.eventPublisher = eventPublisher;
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
        this.processEventEmitter = processEventEmitter;
        this.progressToolListener = progressToolListener;
        this.llmTraceService = llmTraceService;
        this.historyTagBuilder = historyTagBuilder;
        this.toolResultStorage = toolResultStorage;
        this.recipeResolverProvider = recipeResolverProvider;
    }

    /** Setting key driving per-process LLM-trace persistence. */
    public static final String SETTING_TRACE_LLM = "tracing.llm";

    /**
     * Setting key for the deferred-tool-activation TTL. Cascade tenant
     * → project → think-process; values are duration strings (e.g.
     * {@code "15m"}, {@code "1h"}). Default: 15 min. {@code 0}/{@code "0"}
     * disables decay (entries persist until manual cleanup). See
     * {@code planning/tool-schema-deferral.md} §6.
     */
    public static final String SETTING_DEFERRAL_ACTIVATION_TTL = "tooling.deferralActivationTtl";

    /** Hard default when neither setting nor recipe pins the TTL. */
    public static final Duration DEFAULT_DEFERRAL_ACTIVATION_TTL = Duration.ofMinutes(15);

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        log.info("Registered think-engines: {}", engines.keySet());
    }

    /** Registered engine names. */
    public List<String> listEngines() {
        return List.copyOf(engines.keySet());
    }

    public Optional<ThinkEngine> resolve(String name) {
        return Optional.ofNullable(engines.get(name));
    }

    /**
     * Resolves the engine referenced by {@code process.getThinkEngine()}.
     * Throws if no engine is registered under that name.
     */
    public ThinkEngine resolveForProcess(ThinkProcessDocument process) {
        ThinkEngine engine = engines.get(process.getThinkEngine());
        if (engine == null) {
            throw new UnknownThinkEngineException(
                    "Unknown think-engine '" + process.getThinkEngine()
                            + "' for process id='" + process.getId() + "'");
        }
        return engine;
    }

    /**
     * Builds a fresh {@link ThinkEngineContext} for the given process. One
     * context instance per lifecycle call — never cached. The context is
     * pre-bound to the engine's declared {@code allowedTools()} set, so
     * the {@code ContextToolsApi} the engine sees is automatically scoped.
     */
    public ThinkEngineContext newContext(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Process '" + process.getId()
                                + "' references missing session '"
                                + process.getSessionId() + "'"));
        String projectId = session.getProjectId();
        String userId = session.getUserId();
        ThinkEngine engine = resolveForProcess(process);
        // Recipe-applied override beats engine default. Empty allow-set
        // is intentionally restrictive ("this process may invoke no
        // tools") and must be honoured rather than collapsed to "use
        // engine default".
        final Set<String> base = process.getAllowedToolsOverride() != null
                ? process.getAllowedToolsOverride()
                : engine.allowedTools();
        // Per-mode tighten happens lazily on every tools() call —
        // Plan-Mode transitions inside one runTurn (e.g.
        // PLANNING → EXECUTING via START_EXECUTION) must reflect in
        // the next ContextToolsApi without rebuilding the context.
        final String recipeName = process.getRecipeName();
        final String connectionProfile = process.getConnectionProfile();
        java.util.function.BiFunction<
                de.mhus.vance.api.thinkprocess.ProcessMode,
                de.mhus.vance.toolpack.ToolInvocationContext,
                RecipeResolver.ToolFilter> toolFilterResolver =
                (currentMode, scope) -> {
                    RecipeResolver resolver = recipeResolverProvider.getIfAvailable();
                    if (resolver == null) return RecipeResolver.ToolFilter.EMPTY;
                    try {
                        return resolver.toolFilterFor(
                                process.getTenantId(), projectId,
                                recipeName, connectionProfile, currentMode, scope);
                    } catch (RuntimeException e) {
                        log.warn("ThinkEngineService.toolFilterFor failed for "
                                + "process='{}' recipe='{}' profile='{}' mode={}: {}",
                                process.getId(), recipeName, connectionProfile,
                                currentMode, e.toString());
                        return RecipeResolver.ToolFilter.EMPTY;
                    }
                };
        // Resolve the LLM-trace toggle once per turn — engines pay no
        // setting lookup per round-trip. Cascade is tenant → project →
        // think-process so a single noisy process can be flipped on
        // without enabling tracing for the whole tenant.
        String traceFlag = settingService.getStringValueCascade(
                process.getTenantId(), projectId, process.getId(), SETTING_TRACE_LLM);
        boolean traceLlm = traceFlag != null && (
                "true".equalsIgnoreCase(traceFlag.trim())
                || "1".equals(traceFlag.trim())
                || "yes".equalsIgnoreCase(traceFlag.trim())
                || "on".equalsIgnoreCase(traceFlag.trim()));
        Duration decayTtl = resolveDeferralActivationTtl(
                process.getTenantId(), projectId, process.getId());
        // Fresh per-turn sink — engine flushes after persisting its
        // assistant ChatMessageDocument (see ArthurEngine.handleTodoUpdate
        // and the runTurn finally block for the wiring). Sink lives for
        // the lifetime of this context only.
        de.mhus.vance.brain.history.BufferingHistoryTagSink tagSink =
                new de.mhus.vance.brain.history.BufferingHistoryTagSink();
        return new DefaultThinkEngineContext(
                process, projectId, userId, base,
                aiModelService, settingService, chatMessageService,
                toolDispatcher, eventPublisher,
                thinkProcessService, processEventEmitter,
                toolFilterResolver,
                progressToolListener.forProcess(process),
                decayTtl,
                traceLlm,
                llmTraceService,
                historyTagBuilder,
                tagSink,
                toolResultStorage);
    }

    /**
     * Reads the deferral-activation TTL from the settings cascade. Empty
     * / unparseable / blank → falls back to {@link #DEFAULT_DEFERRAL_ACTIVATION_TTL}.
     * Accepts ISO-8601 ({@code "PT15M"}) and short-form ({@code "15m"},
     * {@code "1h"}) durations. {@code "0"} disables decay.
     */
    private Duration resolveDeferralActivationTtl(
            String tenantId, String projectId, String processId) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, processId, SETTING_DEFERRAL_ACTIVATION_TTL);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DEFERRAL_ACTIVATION_TTL;
        }
        String trimmed = raw.trim();
        if ("0".equals(trimmed)) return Duration.ZERO;
        try {
            // Try ISO-8601 first (PT15M), then accept "15m" / "1h" / "30s".
            return Duration.parse(trimmed);
        } catch (RuntimeException e) {
            return parseShortDuration(trimmed)
                    .orElseGet(() -> {
                        log.warn("Unparseable {}='{}', falling back to {}",
                                SETTING_DEFERRAL_ACTIVATION_TTL, trimmed,
                                DEFAULT_DEFERRAL_ACTIVATION_TTL);
                        return DEFAULT_DEFERRAL_ACTIVATION_TTL;
                    });
        }
    }

    private static Optional<Duration> parseShortDuration(String s) {
        if (s.length() < 2) return Optional.empty();
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        String numPart = s.substring(0, s.length() - 1);
        long n;
        try {
            n = Long.parseLong(numPart);
        } catch (NumberFormatException nfe) {
            return Optional.empty();
        }
        return switch (unit) {
            case 's' -> Optional.of(Duration.ofSeconds(n));
            case 'm' -> Optional.of(Duration.ofMinutes(n));
            case 'h' -> Optional.of(Duration.ofHours(n));
            case 'd' -> Optional.of(Duration.ofDays(n));
            default -> Optional.empty();
        };
    }

    // ─── Convenience dispatch ────────────────────────────────────────────
    // Short-circuits the three-step (resolve, newContext, invoke) pattern
    // callers would otherwise repeat.

    public void start(ThinkProcessDocument process) {
        resolveForProcess(process).start(process, newContext(process));
    }

    public void resume(ThinkProcessDocument process) {
        resolveForProcess(process).resume(process, newContext(process));
    }

    public void suspend(ThinkProcessDocument process) {
        resolveForProcess(process).suspend(process, newContext(process));
    }

    public void steer(ThinkProcessDocument process, SteerMessage message) {
        resolveForProcess(process).steer(process, newContext(process), message);
    }

    /**
     * Triggers a fresh lane-turn. Used by the runtime ({@code
     * ProcessEventEmitter}) when the process has new work in its
     * persistent inbox — the engine drains and runs.
     */
    public void runTurn(ThinkProcessDocument process) {
        resolveForProcess(process).runTurn(process, newContext(process));
    }

    public void stop(ThinkProcessDocument process) {
        resolveForProcess(process).stop(process, newContext(process));
    }

    public static class UnknownThinkEngineException extends RuntimeException {
        public UnknownThinkEngineException(String message) {
            super(message);
        }
    }
}
