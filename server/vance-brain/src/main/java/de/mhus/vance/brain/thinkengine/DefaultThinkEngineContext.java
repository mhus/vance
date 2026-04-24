package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * Straightforward {@link ThinkEngineContext} — carries the process and wired
 * services. Created per lifecycle call by {@link ThinkEngineService}.
 */
record DefaultThinkEngineContext(
        ThinkProcessDocument process,
        AiModelService aiModelService,
        SettingService settingService,
        ChatMessageService chatMessageService
) implements ThinkEngineContext {

    @Override
    public String tenantId() {
        return process.getTenantId();
    }

    @Override
    public String sessionId() {
        return process.getSessionId();
    }
}
