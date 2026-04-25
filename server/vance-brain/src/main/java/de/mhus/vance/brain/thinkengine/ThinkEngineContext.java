package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * Per-call access surface handed to a {@link ThinkEngine}. Built fresh by
 * {@link ThinkEngineService} for every lifecycle invocation — engines must
 * not cache it.
 *
 * <p>v1 only exposes the pieces Zaphod actually consumes:
 * {@link #process()}, {@link #aiModelService()}, {@link #settingService()},
 * {@link #chatMessageService()}. The remaining pieces (event publisher,
 * pending-queue drain, memory API, tool dispatcher, process orchestrator)
 * will be added here as those subsystems arrive. The interface is kept
 * intentionally thin so we don't pre-carve abstractions before the first
 * user shapes them.
 */
public interface ThinkEngineContext {

    /** The process this call is bound to. */
    ThinkProcessDocument process();

    /** Tenant id of the process's session — shortcut for the common lookup. */
    String tenantId();

    /** Session id the process belongs to ({@code SessionDocument.sessionId}). */
    String sessionId();

    /**
     * Project id the session lives under ({@code ProjectDocument.name}).
     * Resolved once per context build via the session lookup; cached for
     * the lifetime of the call.
     */
    String projectId();

    /** Access to AI model instantiation. */
    AiModelService aiModelService();

    /** Typed-settings lookup with scope-aware helpers. */
    SettingService settingService();

    /** Read/write access to the persistent chat log of this process. */
    ChatMessageService chatMessageService();

    /**
     * Scope-bound tools API. Lists the tools visible to this process
     * and dispatches invocations. Fresh per call — do not cache.
     */
    ContextToolsApi tools();

    /**
     * Sink for server-initiated notifications to the connected client
     * (streaming chunks, progress updates). Optimistic delivery — if
     * no client is listening, {@code publish} returns {@code false}
     * and the engine should continue regardless.
     */
    ClientEventPublisher events();
}
