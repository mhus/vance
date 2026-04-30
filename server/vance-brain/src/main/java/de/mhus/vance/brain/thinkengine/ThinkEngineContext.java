package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Per-call access surface handed to a {@link ThinkEngine}. Built fresh by
 * {@link ThinkEngineService} for every lifecycle invocation — engines must
 * not cache it.
 *
 * <p>The interface is kept intentionally thin — methods are added here
 * as engines actually need them, not pre-carved.
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

    /**
     * Owner-userId of the session, or {@code null} when unavailable
     * (system-launched processes without a human owner). Resolved once
     * per context build via the session lookup.
     */
    @Nullable String userId();

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

    /**
     * Atomically reads and clears this process's pending inbox.
     * Returns the messages in arrival order; never {@code null}.
     *
     * <p>An engine that handles all queued events in one turn calls
     * this at the top of {@code steer} / {@code resume} and folds
     * everything into a single LLM round-trip. The persistence form
     * is translated by {@code SteerMessageCodec} so engines see only
     * the sealed {@link SteerMessage} hierarchy.
     */
    List<SteerMessage> drainPending();

    /**
     * Sibling-process control surface — orchestrators (Arthur,
     * deep-think) reach for their session-mates and notify their
     * parent through this API.
     */
    ProcessOrchestrator processes();

    /**
     * Whether LLM-roundtrip persistence is enabled for this turn.
     * Resolved once at context build via the {@code tracing.llm} setting
     * (cascade tenant → project → think-process), so engines don't pay
     * a setting lookup per round-trip. When {@code true}, engines should
     * record their LLM I/O via {@link #llmTraceService()}.
     */
    boolean traceLlm();

    /**
     * Service for persisting LLM-roundtrip trace records. Engines call
     * this only when {@link #traceLlm()} is {@code true}; the service
     * itself is always available so downstream code can record without
     * conditional injection.
     */
    LlmTraceService llmTraceService();
}
