package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
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

    public ThinkEngineService(
            List<ThinkEngine> engineBeans,
            AiModelService aiModelService,
            SettingService settingService,
            ChatMessageService chatMessageService,
            ToolDispatcher toolDispatcher,
            ClientEventPublisher eventPublisher) {
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
     * context instance per lifecycle call — never cached.
     */
    public ThinkEngineContext newContext(ThinkProcessDocument process) {
        return new DefaultThinkEngineContext(
                process, aiModelService, settingService, chatMessageService,
                toolDispatcher, eventPublisher);
    }

    // ─── Convenience dispatch ────────────────────────────────────────────
    // Short-circuits the three-step (resolve, newContext, invoke) pattern
    // callers would otherwise repeat. Will later move behind the lane
    // scheduler once that exists — callers keep this API.

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

    public void stop(ThinkProcessDocument process) {
        resolveForProcess(process).stop(process, newContext(process));
    }

    public static class UnknownThinkEngineException extends RuntimeException {
        public UnknownThinkEngineException(String message) {
            super(message);
        }
    }
}
