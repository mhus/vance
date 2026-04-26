package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Set;

/**
 * Straightforward {@link ThinkEngineContext} — carries the process and
 * wired services. Created per lifecycle call by
 * {@link ThinkEngineService}; {@code projectId} is resolved by the
 * service via the session lookup and threaded in here.
 */
record DefaultThinkEngineContext(
        ThinkProcessDocument process,
        String projectId,
        AiModelService aiModelService,
        SettingService settingService,
        ChatMessageService chatMessageService,
        ToolDispatcher toolDispatcher,
        ClientEventPublisher eventPublisher,
        ThinkProcessService thinkProcessService,
        ProcessEventEmitter processEventEmitter,
        Set<String> allowedTools
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
                null);
        return new ContextToolsApi(toolDispatcher, scope, allowedTools);
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
