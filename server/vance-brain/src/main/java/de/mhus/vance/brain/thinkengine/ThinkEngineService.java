package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.progress.ProgressToolListener;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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

    /** Setting key driving per-process LLM-trace persistence. */
    public static final String SETTING_TRACE_LLM = "tracing.llm";

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
            LlmTraceService llmTraceService) {
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
        java.util.Set<String> base = process.getAllowedToolsOverride() != null
                ? process.getAllowedToolsOverride()
                : engine.allowedTools();
        // Per-mode tighten: Arthur drops to read-only in EXPLORING/PLANNING,
        // other engines pass through unchanged.
        java.util.Set<String> allowed = engine.filterAllowedToolsForMode(
                base, process.getMode());
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
        return new DefaultThinkEngineContext(
                process, projectId, userId,
                aiModelService, settingService, chatMessageService,
                toolDispatcher, eventPublisher,
                thinkProcessService, processEventEmitter,
                allowed,
                progressToolListener.forProcess(process),
                traceLlm,
                llmTraceService);
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
