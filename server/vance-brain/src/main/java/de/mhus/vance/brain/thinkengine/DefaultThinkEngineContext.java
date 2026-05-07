package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Straightforward {@link ThinkEngineContext} — carries the process and
 * wired services. Created per lifecycle call by
 * {@link ThinkEngineService}; {@code projectId} is resolved by the
 * service via the session lookup and threaded in here.
 *
 * <p>The {@code allowedToolsResolver} is a callback re-evaluated on
 * every {@link #tools()} call so the effective allow-set tracks the
 * process's current {@code mode}. This matters for Plan-Mode where
 * a single {@code runTurn} can transition through multiple modes
 * (e.g. PLANNING → EXECUTING via {@code START_EXECUTION}); a snapshot
 * of {@code allowedTools} taken at context-build time would be stale
 * for the EXECUTING continuation turn.
 */
record DefaultThinkEngineContext(
        ThinkProcessDocument process,
        String projectId,
        @Nullable String userId,
        AiModelService aiModelService,
        SettingService settingService,
        ChatMessageService chatMessageService,
        ToolDispatcher toolDispatcher,
        ClientEventPublisher eventPublisher,
        ThinkProcessService thinkProcessService,
        ProcessEventEmitter processEventEmitter,
        BiFunction<de.mhus.vance.api.thinkprocess.ProcessMode, ToolInvocationContext, Set<String>> allowedToolsResolver,
        ToolInvocationListener toolInvocationListener,
        boolean traceLlm,
        LlmTraceService llmTraceService
) implements ThinkEngineContext {

    @Override
    public String tenantId() {
        return process.getTenantId();
    }

    @Override
    public String sessionId() {
        return process.getSessionId();
    }

    @Override
    public ContextToolsApi tools() {
        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                projectId,
                process.getSessionId(),
                process.getId(),
                userId);
        // Re-resolve the allow-set every call against the process's
        // current mode — see class doc for why a snapshot won't do.
        Set<String> allowed = allowedToolsResolver.apply(process.getMode(), scope);
        return new ContextToolsApi(toolDispatcher, scope, allowed, toolInvocationListener);
    }

    @Override
    public ClientEventPublisher events() {
        return eventPublisher;
    }

    @Override
    public List<SteerMessage> drainPending() {
        return SteerMessageCodec.toMessages(
                thinkProcessService.drainPending(process.getId()));
    }

    @Override
    public ProcessOrchestrator processes() {
        return new DefaultProcessOrchestrator(
                process, thinkProcessService, processEventEmitter);
    }
}
