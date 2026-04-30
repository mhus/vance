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
import org.jspecify.annotations.Nullable;

/**
 * Straightforward {@link ThinkEngineContext} — carries the process and
 * wired services. Created per lifecycle call by
 * {@link ThinkEngineService}; {@code projectId} is resolved by the
 * service via the session lookup and threaded in here.
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
        Set<String> allowedTools,
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
        return new ContextToolsApi(toolDispatcher, scope, allowedTools, toolInvocationListener);
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
