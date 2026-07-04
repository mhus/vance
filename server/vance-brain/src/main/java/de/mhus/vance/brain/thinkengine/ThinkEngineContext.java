package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.history.TurnReasoningBuffer;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     *
     * <p>This is the engine's <em>home</em> — where local writes
     * ({@code doc_*}, {@code scratch_*}, activity log) land. Eddie's
     * "spot" pointer for cross-project work is exposed separately via
     * {@link #workingProjectId()}.
     */
    String projectId();

    /**
     * Eddie's "spot" — the foreign project currently coordinated, set
     * via {@code SWITCH_PROJECT} / {@code DELEGATE_PROJECT}-side-effect
     * / {@code /project} slash-command. {@code null} when no working
     * project has been selected yet, or for any non-Eddie engine.
     *
     * <p>Read this when an engine needs to address the working project
     * directly (e.g. relay messages, mediation). Tool implementations
     * should instead reach it via {@code ToolInvocationContext} so the
     * LLM cannot override it with a hallucinated param.
     */
    @Nullable String workingProjectId();

    /**
     * Spot-bound resolution helper — equivalent to
     * {@link #workingProjectId()} but throws {@link IllegalStateException}
     * when no working project is selected. The error message surfaces
     * back to the LLM via the standard tool-error path, so a premature
     * cross-project call self-corrects.
     */
    default String requireWorkingProjectId() {
        String spot = workingProjectId();
        if (spot == null || spot.isBlank()) {
            throw new IllegalStateException(
                    "No working project selected — emit SWITCH_PROJECT before using this tool");
        }
        return spot;
    }

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
     * Engine emits a semantic reply for the parent process — the
     * worker's complete answer for one turn. Routed twice:
     * <ol>
     *   <li>{@code PROCESS_PROGRESS} push to the session's clients so
     *       the UI can render the reply in real time.</li>
     *   <li>{@code SteerMessage.Reply} appended to the parent's
     *       persistent pending inbox (if a parent exists). The parent's
     *       lane wakes and drains it on its next turn.</li>
     * </ol>
     *
     * <p>Discipline: each call ships a complete, self-contained answer.
     * No incremental fragments — those go through
     * {@code ProgressEmitter.emitStatus} instead. An engine may emit
     * multiple Replies over its lifetime as long as each one is
     * independently meaningful to the parent. See
     * {@code planning/process-engine-reply-channel.md}.
     *
     * @param content         the full reply text — non-blank
     * @param inResponseToAt  timestamp of the user-input turn the
     *                        worker was answering, or {@code null} for
     *                        engine-driven replies
     * @param payload         optional structured side-channel data
     */
    void emitReply(
            String content,
            @Nullable Instant inResponseToAt,
            @Nullable Map<String, Object> payload);

    /** Convenience overload — text only. */
    default void emitReply(String content) {
        emitReply(content, null, null);
    }

    /**
     * Engine emits an <em>interim</em> reply — a live working-log entry
     * that lets the user follow a long-running engine loop in real time
     * (Lunkwill narrates between tool batches). Differs from
     * {@link #emitReply} in two ways: the message is flagged
     * {@code interim} so clients can render it visually dimmed, and
     * parent-inbox routing is skipped (interims are pure UI signal,
     * only the canonical reply at turn-end crosses the worker→parent
     * boundary). Blank content is silently dropped.
     */
    void emitInterimReply(String content, @Nullable Instant inResponseToAt);

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

    /**
     * Per-turn buffer for history marker tags. The tool-dispatcher hook
     * writes {@code TOOL_CALL:*} / {@code RESOURCE:*} / {@code FILE_EDIT}
     * / {@code DOC_EDIT} / {@code ERROR} tags into it as tools run, and
     * engines emit {@code PLAN_STEP_*} / {@code MODE:*} tags directly.
     * The engine flushes the buffer to its assistant
     * {@link ChatMessageService chat-message} immediately after persisting
     * the turn, so tags land on the right turn id. See
     * {@code planning/process-history-search.md} §5.
     */
    BufferingHistoryTagSink historyTagSink();

    /**
     * Per-turn accumulator for the model's reasoning ("thinking") text.
     * The structured-action loop appends each iteration's extracted
     * reasoning; the engine reads {@link TurnReasoningBuffer#snapshot()}
     * when persisting the turn's assistant {@link ChatMessageService
     * chat-message} and stores it in the {@code thinking} field so the
     * client can show it as a collapsible section. Fresh per turn — same
     * lifecycle as {@link #historyTagSink()}.
     */
    TurnReasoningBuffer reasoning();
}
