package de.mhus.vance.brain.thinkengine.action;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.history.TurnReasoningBuffer;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.Lc4jSchema;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Base class for engines that drive their turn through a single
 * structured action call instead of free-form tool-and-text. The
 * engine declares a set of {@link EngineAction} types via JSON
 * schema; the LLM is forced (by validator + correction loop) to
 * emit exactly one such action per turn. The base class then hands
 * the parsed action to {@link #handleAction} which the subclass
 * implements per type.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A free-form orchestrator emits free text, work-tool calls
 * <em>and</em> a final-marker tool — three slots that conflict in
 * practice (filler messages, hallucinated worker names, premature
 * respond). Collapsing all of that into a single discriminated
 * action removes the format-correction loop, the
 * {@code respond}-tool short-circuit, and most of the prompt-
 * engineering needed to keep the LLM disciplined.
 *
 * <h2>What stays the same</h2>
 *
 * <ul>
 *   <li><b>ChatBehavior error handling</b>. The same
 *       {@link AiChat} is used; resilient retries and primary→
 *       fallback model chains keep working.</li>
 *   <li><b>Read-only tools</b>. Subclasses can still pass non-
 *       action tools (e.g. {@code recipe_list}, {@code manual_read})
 *       in {@code readToolSpecs}; the model can call them
 *       multiple times before emitting the final action.</li>
 *   <li><b>Streaming</b>. Tokens are flushed through
 *       {@link ChunkBatcher} the same way as before — the
 *       structured-action JSON streams into the chat-stream channel
 *       too, so clients render incremental progress.</li>
 * </ul>
 *
 * <h2>Failure modes</h2>
 *
 * <ul>
 *   <li><b>Malformed JSON / missing fields</b> — the LLM is told
 *       what's missing and asked to retry, up to the caller's
 *       correction budget
 *       ({@link de.mhus.vance.brain.ai.ModelInfo#actionLoopCorrections()}).
 *       After that we fall back
 *       to the longest free-text the model produced this turn,
 *       packaged as an {@code ANSWER}-style outcome (preserves
 *       work, never crashes).</li>
 *   <li><b>LLM stream failure</b> (Gemini "neither text nor
 *       function call", retry budget exhausted) — same fallback
 *       to bestFreeText; if there is none we re-throw.</li>
 *   <li><b>Unknown action type</b> — handled identically to
 *       malformed JSON (correction + fallback).</li>
 * </ul>
 */
public abstract class StructuredActionEngine implements ThinkEngine {

    private static final Logger log = LoggerFactory.getLogger(StructuredActionEngine.class);

    private final StreamingProperties streamingProperties;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;
    /**
     * System-prompt builder shared by every single-action engine.
     * Bundles the Pebble renderer and the addon-fragment registry so
     * subclasses inject one bean and call
     * {@code composer.compose(process, engineDefault, ctxBuilder)} in
     * one line — addon fragments for the engine are merged automatically.
     */
    protected final SystemPromptComposer composer;

    protected StructuredActionEngine(
            StreamingProperties streamingProperties,
            LlmCallTracker llmCallTracker,
            ObjectMapper objectMapper,
            SystemPromptComposer composer) {
        this.streamingProperties = streamingProperties;
        this.llmCallTracker = llmCallTracker;
        this.objectMapper = objectMapper;
        this.composer = composer;
    }

    // ─────────────────────────────────────────────
    // Subclass contract
    // ─────────────────────────────────────────────

    /** Tool name the LLM must call to terminate a turn. Engine-specific (e.g. {@code arthur_action}). */
    protected abstract String actionToolName();

    /** Description shown to the LLM as part of the tool spec. */
    protected abstract String actionToolDescription();

    /**
     * JSON-schema map describing every supported action type.
     * Convention: {@code type} is a required string enum,
     * {@code reason} is required, type-specific extras are listed
     * but typically optional in the flat schema (subclass validates
     * per-type required fields inside {@link #handleAction}).
     */
    protected abstract Map<String, Object> actionToolSchema();

    /**
     * Closed set of valid {@code type} values. Used by the JSON
     * validator to reject unknown types — independent of whatever
     * enum the schema declares (covers the case where the LLM
     * ignores the enum constraint).
     */
    protected abstract Set<String> supportedActionTypes();

    /**
     * Turn a plain free-text reply — the model answered in prose without
     * wrapping it in the action tool-call — into the engine's terminal
     * "just deliver this text" action (typically {@code ANSWER}). Returns
     * {@code null} when the engine has no such action or the text isn't
     * answer-worthy (blank).
     *
     * <p>Consulted only at correction exhaustion, after
     * {@link #tryParseActionFromFreeText}. Lets a model that reliably
     * skips the action wrapper (e.g. DeepSeek-V4 via a strict
     * OpenAI-compatible proxy) land a clean action instead of the
     * "gave-up" free-text fallback — which for a delegated worker would
     * otherwise close INCOMPLETE and hand the parent a stale note. Pair
     * with a low {@code actionLoopCorrections} (0) on such models so the
     * wrap happens immediately, without burning correction rounds.
     */
    protected @Nullable EngineAction answerActionFromText(String text) {
        return null;
    }

    /**
     * Engine-specific dispatch. Called once per turn with the
     * parsed action. Subclass returns the chat-message to persist
     * (may be empty/null = no chat append) and the post-turn
     * {@code awaiting_user_input} flag.
     */
    protected abstract ActionTurnOutcome handleAction(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx);

    /**
     * Whether {@code action} ends the turn (chat message, BLOCKED on
     * user, hand-off to worker) or is a state-mutating step the LLM
     * should chain on. Default: every action terminates. Subclasses
     * (e.g. ArthurEngine for plan-mode {@code TODO_UPDATE} /
     * {@code START_PLAN} / {@code START_EXECUTION}) override to mark
     * those as continuing — the loop applies them in-place and feeds
     * the outcome back as a tool-result so the LLM has memory of the
     * state change inside the same turn.
     */
    protected boolean isTerminalAction(EngineAction action) {
        return true;
    }

    /**
     * Apply a non-terminal action within the action loop. Returns a
     * short directive string the LLM sees as the action tool's
     * tool-result — should describe what happened plus what to do
     * next. Subclasses overriding {@link #isTerminalAction} must
     * override this. Default throws.
     */
    protected String applyContinuingAction(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        throw new UnsupportedOperationException(
                "applyContinuingAction not implemented for "
                        + name() + " action='" + action.type() + "'");
    }

    /**
     * Final outcome of one structured-action turn. {@code chatMessage}
     * is the text appended to the assistant chat log (use
     * {@code null}/empty for silent actions like {@code WAIT} that
     * shouldn't surface in the conversation history).
     * {@code awaitingUserInput} drives the post-turn
     * {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus}:
     * {@code true} → BLOCKED, {@code false} → IDLE.
     */
    public record ActionTurnOutcome(
            @Nullable String chatMessage,
            boolean awaitingUserInput,
            @Nullable Map<String, Object> chatMessageMeta) {

        /**
         * Convenience constructor for the common case where the
         * outcome carries no structured chat-message metadata.
         * Delegates to the canonical 3-arg form with {@code null}
         * meta. All legacy 2-arg callers keep working.
         */
        public ActionTurnOutcome(@Nullable String chatMessage, boolean awaitingUserInput) {
            this(chatMessage, awaitingUserInput, null);
        }
    }

    // ─────────────────────────────────────────────
    // The action loop
    // ─────────────────────────────────────────────

    /**
     * Runs the structured-action loop. Iterates LLM calls until one
     * emits a parseable action of a {@link #supportedActionTypes()
     * supported type} with a non-blank {@code reason}. Read-only
     * tool calls in earlier iterations are dispatched normally.
     *
     * @param aiChat        chat handle from EngineChatFactory (carries
     *                      ChatBehavior resilience semantics)
     * @param readToolSpecsFactory derives the per-iteration list of
     *                      side-effect-free tool specs from the
     *                      current {@link ContextToolsApi}. Called
     *                      once at loop start and again after any
     *                      iteration that invoked read tools, so
     *                      tools activated mid-loop via
     *                      {@code describe_tool} become visible to
     *                      the next iteration. The action tool is
     *                      appended by the base class — the factory
     *                      does NOT include it.
     * @param messages      mutable conversation buffer; the loop
     *                      appends to it as it iterates
     * @param ctx           engine context — also the source of the
     *                      live {@link ContextToolsApi} (re-fetched
     *                      per iteration to pick up deferred-tool
     *                      activations)
     * @param process       the running process
     * @param maxIters      per-turn iteration cap
     * @param modelAlias    label for LLM-call telemetry
     * @param maxCorrections action-loop "free text without tool call"
     *                      correction budget. Engines pass a per-model
     *                      value from {@code ai-models.yaml} (via
     *                      {@link de.mhus.vance.brain.ai.ModelInfo#actionLoopCorrections()},
     *                      which defaults to
     *                      {@link de.mhus.vance.brain.ai.ModelInfo#DEFAULT_ACTION_LOOP_CORRECTIONS})
     *                      so chatty / silent-prone models get more
     *                      head-room.
     * @return the parsed action plus enough conversation context for
     *         the subclass to synthesise its chat message and status
     */
    protected ActionLoopResult runStructuredActionLoop(
            AiChat aiChat,
            Function<ContextToolsApi, List<ToolSpecification>> readToolSpecsFactory,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            int maxIters,
            String modelAlias,
            int maxCorrections) {

        // Fresh tools + spec list per loop. Refreshed after any
        // iteration that called read tools so describe_tool
        // activations take effect on the very next iteration.
        ContextToolsApi tools = ctx.tools();
        List<ToolSpecification> allSpecs = new ArrayList<>(readToolSpecsFactory.apply(tools));
        allSpecs.add(buildActionToolSpec());

        int corrections = 0;
        int toolInvocations = 0;
        String bestFreeText = "";

        for (int iter = 0; iter < maxIters; iter++) {
            ChatRequest req = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(allSpecs)
                    .build();

            AiMessage reply;
            try {
                reply = streamOneIteration(aiChat, req, ctx, process, modelAlias);
            } catch (RuntimeException e) {
                if (!bestFreeText.isEmpty()) {
                    log.warn(
                            "{} id='{}' action-loop LLM failure ({}) — falling back to best free-text seen ({} chars)",
                            name(), process.getId(), e.toString(), bestFreeText.length());
                    return ActionLoopResult.fallback(bestFreeText, "llm-failure",
                            e, toolInvocations);
                }
                log.warn("{} id='{}' action-loop LLM failure with no recoverable text",
                        name(), process.getId());
                throw e;
            }

            String replyText = reply.text();
            if (replyText != null && replyText.length() > bestFreeText.length()) {
                bestFreeText = replyText;
            }

            // Capture the model's raw streamed narration ("thoughts")
            // for this iteration, verbatim — this is exactly the text the
            // user saw stream live (reasoning models emit their
            // <think>… monologue here). It is dropped from the final
            // user-facing content (which comes from the structured action
            // message), so we accumulate it per turn and persist it in
            // the assistant message's `thinking` field to stay reviewable
            // after the stream is replaced by the final answer.
            TurnReasoningBuffer reasoning = ctx.reasoning();
            if (reasoning != null) {
                reasoning.append(replyText);
            }

            if (!reply.hasToolExecutionRequests()) {
                if (corrections < maxCorrections) {
                    log.info(
                            "{} id='{}' action-loop: free text without action call, correcting ({}/{})",
                            name(), process.getId(),
                            corrections + 1, maxCorrections);
                    messages.add(reply);
                    messages.add(SystemMessage.from(noActionCorrection()));
                    corrections++;
                    continue;
                }
                // Recovery: the LLM sometimes refuses the tool-call
                // contract and emits the JSON payload as plain content
                // text instead. Without recovery this leaks raw
                // {"type":"…","reason":"…"} into the chat as the
                // assistant's "reply" — confusing and broken UX.
                // Parse the free text as if it were the tool call
                // arguments; if it produces a valid action, dispatch
                // that instead of falling through. Conservative parse
                // (must be a single JSON object, must have a known
                // type) so prose text doesn't get hijacked.
                EngineAction recovered = tryParseActionFromFreeText(bestFreeText);
                if (recovered != null) {
                    log.info(
                            "{} id='{}' action-loop: recovered '{}' action from free-text JSON "
                                    + "after exhausting corrections",
                            name(), process.getId(), recovered.type());
                    return ActionLoopResult.action(recovered, toolInvocations);
                }
                // The model answered in prose without the action wrapper.
                // Let the engine wrap that prose as its terminal answer
                // action (clean action → clean DONE for workers) instead
                // of the gave-up free-text fallback.
                EngineAction wrapped = answerActionFromText(bestFreeText);
                if (wrapped != null) {
                    log.info(
                            "{} id='{}' action-loop: wrapped free-text as '{}' action "
                                    + "(model emitted no action call)",
                            name(), process.getId(), wrapped.type());
                    return ActionLoopResult.action(wrapped, toolInvocations);
                }
                log.warn(
                        "{} id='{}' action-loop: out of corrections, falling back to free-text",
                        name(), process.getId());
                // Sanitize the fallback before it lands in the user-
                // facing chat. The LLM sometimes regurgitates the
                // <process-event> markers from its drain (treating
                // them as content to relay), or includes a fenced
                // ```json action block. Neither belongs in chat — the
                // first is an internal context-cue, the second is a
                // failed-tool-call escapee. Strip both so the user
                // sees only the prose the LLM actually wrote, not the
                // structural plumbing.
                String sanitized = sanitizeFallbackText(bestFreeText);
                return ActionLoopResult.fallback(sanitized, "no-action-tool-call",
                        null, toolInvocations);
            }

            // Split: action call vs. read calls. The action call (if
            // present) is consumed by the loop itself; read calls go
            // through the regular tool-dispatch path so their results
            // come back into the conversation for the next iteration.
            ToolExecutionRequest actionCall = null;
            List<ToolExecutionRequest> readCalls = new ArrayList<>();
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                if (actionToolName().equals(call.name())) {
                    if (actionCall == null) {
                        actionCall = call;
                    }
                } else {
                    readCalls.add(call);
                }
            }

            messages.add(reply);
            boolean anyReadToolFailed = false;
            for (ToolExecutionRequest call : readCalls) {
                ReadToolOutcome outcome = invokeReadTool(tools, call, process.getId());
                messages.add(ToolExecutionResultMessage.from(call, outcome.json()));
                toolInvocations++;
                if (outcome.failed()) anyReadToolFailed = true;
            }

            // Refresh tool view after any read-tool dispatch so a
            // describe_tool activation in this iteration is visible
            // to the next one — DefaultThinkEngineContext.tools()
            // re-reads activatedDeferredTools from Mongo each call.
            if (!readCalls.isEmpty()) {
                tools = ctx.tools();
                allSpecs = new ArrayList<>(readToolSpecsFactory.apply(tools));
                allSpecs.add(buildActionToolSpec());
            }

            if (actionCall == null) {
                // Read tools were called, but the LLM didn't commit
                // to an action yet. Loop and let it see the results.
                continue;
            }

            // Concurrent emit: read-tool + action in the same reply.
            // If any read-tool failed, the action's content was written
            // under the assumption that the tool succeeded — claiming
            // success when there is none would hallucinate a result to
            // the user. Force a re-evaluation iteration so the model
            // sees the error and revises (or ASK_USER / different
            // action).
            if (anyReadToolFailed) {
                log.info(
                        "{} id='{}' action-loop: read-tool failure alongside action — re-prompting",
                        name(), process.getId());
                messages.add(SystemMessage.from(
                        "One or more read-tool calls in your previous reply"
                                + " returned an error. Do NOT emit an action"
                                + " whose content assumes those tools"
                                + " succeeded (e.g. don't say a document was"
                                + " saved if the save call errored). Look at"
                                + " the tool-result messages above and emit"
                                + " a fresh "
                                + actionToolName()
                                + " that reflects the actual outcome."));
                continue;
            }

            // Parse + validate the action.
            ParseResult parsed = parseAction(actionCall);
            if (!parsed.valid()) {
                if (corrections < maxCorrections) {
                    log.info(
                            "{} id='{}' action-loop: invalid action ({}), correcting ({}/{})",
                            name(), process.getId(), parsed.error(),
                            corrections + 1, maxCorrections);
                    messages.add(ToolExecutionResultMessage.from(actionCall,
                            invalidActionToolResult(parsed.error())));
                    corrections++;
                    continue;
                }
                log.warn(
                        "{} id='{}' action-loop: invalid action after {} corrections, falling back",
                        name(), process.getId(), corrections);
                return ActionLoopResult.fallback(bestFreeText, "invalid-action",
                        null, toolInvocations);
            }

            // Semantic gate — subclass-defined post-parse rejection
            // (e.g. spawn-action on event-only turn). Feed the hint
            // back as a correction so the LLM re-emits a valid action
            // instead of leaking the engine-internal reject string
            // into the chat as the assistant's reply.
            String semanticReject =
                    validateActionSemantics(parsed.action(), process, ctx);
            if (semanticReject != null) {
                if (corrections < maxCorrections) {
                    log.info(
                            "{} id='{}' action-loop: semantic reject ({}), correcting ({}/{})",
                            name(), process.getId(),
                            summarise(semanticReject),
                            corrections + 1, maxCorrections);
                    messages.add(ToolExecutionResultMessage.from(actionCall,
                            semanticRejectToolResult(semanticReject)));
                    corrections++;
                    continue;
                }
                log.warn(
                        "{} id='{}' action-loop: semantic reject after {} corrections, falling back",
                        name(), process.getId(), corrections);
                return ActionLoopResult.fallback(bestFreeText, "semantic-reject",
                        null, toolInvocations);
            }

            log.info(
                    "{} id='{}' action='{}' reason='{}'",
                    name(), process.getId(),
                    parsed.action().type(), summarise(parsed.action().reason()));

            // Continuing actions (e.g. Arthur's TODO_UPDATE / START_PLAN /
            // START_EXECUTION) don't terminate the turn — they mutate
            // engine state, the LLM should keep working. We apply the
            // action right here, feed the result back as the action
            // tool's tool-result so the LLM has a record of "I just did
            // X, here's the new state", and continue iterating. Without
            // this, silent actions cause an LLM-amnesia loop: the next
            // turn rebuilds the prompt from scratch and the model
            // repeats the same action because it has no memory of
            // having emitted it.
            if (!isTerminalAction(parsed.action())) {
                String feedback;
                try {
                    feedback = applyContinuingAction(parsed.action(), process, ctx);
                } catch (RuntimeException e) {
                    log.warn("{} id='{}' continuing-action handler failed: {}",
                            name(), process.getId(), e.toString(), e);
                    feedback = "(internal: applyContinuingAction failed: "
                            + e.getMessage() + ")";
                }
                if (feedback == null || feedback.isBlank()) {
                    feedback = "Action " + parsed.action().type() + " applied.";
                }
                messages.add(ToolExecutionResultMessage.from(actionCall, feedback));
                toolInvocations++;
                continue;
            }

            return ActionLoopResult.action(parsed.action(), toolInvocations);
        }

        log.warn(
                "{} id='{}' action-loop: exceeded {} iterations, falling back (toolInvocations={})",
                name(), process.getId(), maxIters, toolInvocations);
        return ActionLoopResult.fallback(bestFreeText, "max-iters", null, toolInvocations);
    }

    /**
     * Result of the action loop. Either the LLM produced a valid
     * action ({@link #action()} non-null), or we exhausted retries
     * and fell back to the best free-text we could see ({@link
     * #fallbackText()} non-null).
     */
    public record ActionLoopResult(
            @Nullable EngineAction action,
            @Nullable String fallbackText,
            @Nullable String fallbackReason,
            @Nullable Throwable cause,
            int toolInvocations) {

        static ActionLoopResult action(EngineAction a, int toolInvocations) {
            return new ActionLoopResult(a, null, null, null, toolInvocations);
        }

        static ActionLoopResult fallback(String text, String reason,
                                          @Nullable Throwable cause,
                                          int toolInvocations) {
            return new ActionLoopResult(null, text == null ? "" : text, reason,
                    cause, toolInvocations);
        }

        public boolean isAction() {
            return action != null;
        }

        public boolean isFallback() {
            return action == null;
        }

        /**
         * Whether the loop actually got something done — read tool
         * dispatched, continuing-action handler invoked. Plan-mode
         * engines use this to distinguish "model is genuinely stuck"
         * from "model is mid-multi-file-refactor and just hit the
         * per-turn iteration cap".
         */
        public boolean madeProgress() {
            return toolInvocations > 0;
        }
    }

    // ─────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────

    private ToolSpecification buildActionToolSpec() {
        return ToolSpecification.builder()
                .name(actionToolName())
                .description(actionToolDescription())
                .parameters(Lc4jSchema.toObjectSchema(actionToolSchema()))
                .build();
    }

    /**
     * Parses the JSON arguments of an action call into an
     * {@link EngineAction}. Returns a {@code ParseResult} carrying
     * either the parsed action or a human-readable error string
     * suitable for feeding back to the LLM as the tool result.
     */
    private ParseResult parseAction(ToolExecutionRequest call) {
        String raw = call.arguments();
        if (raw == null || raw.isBlank()) {
            return ParseResult.error("empty action arguments");
        }
        Map<String, Object> json;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            json = parsed;
        } catch (RuntimeException e) {
            return ParseResult.error("not valid JSON: " + e.getMessage());
        }
        Object typeVal = json.get("type");
        if (!(typeVal instanceof String typeStr) || typeStr.isBlank()) {
            return ParseResult.error("missing required field 'type'");
        }
        if (!supportedActionTypes().contains(typeStr)) {
            return ParseResult.error(
                    "unknown action type '" + typeStr
                            + "'. Supported types: " + supportedActionTypes());
        }
        Object reasonVal = json.get("reason");
        if (!(reasonVal instanceof String reasonStr) || reasonStr.isBlank()) {
            return ParseResult.error(
                    "missing required field 'reason' — every action must explain"
                            + " why it was chosen");
        }
        // Pass everything else through as params so subclass can read
        // type-specific fields. Strip type/reason since they're top-level.
        Map<String, Object> params = new LinkedHashMap<>(json);
        params.remove("type");
        params.remove("reason");
        return ParseResult.ok(new EngineAction(typeStr, reasonStr, params));
    }

    private record ParseResult(@Nullable EngineAction action, @Nullable String error) {
        static ParseResult ok(EngineAction a) { return new ParseResult(a, null); }
        static ParseResult error(String e) { return new ParseResult(null, e); }
        boolean valid() { return action != null; }
    }

    /**
     * Recovery path for LLMs that, after correction attempts, still
     * emit the structured action as plain content text instead of as
     * a tool call — typically a bare JSON object like
     * {@code {"type":"WAIT","reason":"…"}}. Returns the parsed action
     * when the free text is exactly such a payload (optionally wrapped
     * in a ```json fence); returns {@code null} otherwise.
     *
     * <p>Conservative on purpose: only triggers when the trimmed text
     * starts with {@code {} and ends with {@code }}, has a valid
     * {@code type} from {@link #supportedActionTypes()}, and parses as
     * JSON. Prose with an embedded JSON snippet is left alone so we
     * don't hijack legitimate free-text answers.
     */
    private @Nullable EngineAction tryParseActionFromFreeText(@Nullable String text) {
        if (text == null) return null;
        // Try three increasingly tolerant extractions:
        //   (a) entire text is a JSON object — original behaviour
        //   (b) entire text wrapped in a ```json … ``` fence
        //   (c) text is prose with an embedded ```json … ``` block
        //       (observed Gemini emission: "Hier ist die Aktion: ```json
        //       {…} ```"). Conservative: must contain a known action
        //       type, otherwise we leave prose alone.
        for (String candidate : extractJsonCandidates(text)) {
            EngineAction recovered = tryParseAction(candidate);
            if (recovered != null) return recovered;
        }
        return null;
    }

    /**
     * Yields parse-candidates in priority order from a free-text reply:
     * the whole text, the de-fenced text, and (last) the first
     * ```json … ``` block embedded anywhere. Caller picks the first one
     * that yields a known action type — see
     * {@link #tryParseActionFromFreeText}.
     */
    private static List<String> extractJsonCandidates(String text) {
        List<String> out = new ArrayList<>(3);
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) out.add(trimmed);
        String defenced = stripJsonCodeFence(trimmed).trim();
        if (!defenced.isEmpty() && !defenced.equals(trimmed)) {
            out.add(defenced);
        }
        // Embedded fence anywhere in the text — observed when the LLM
        // emits intro prose then a fenced action block as a "show-off"
        // of what it intended to call.
        int fenceStart = text.indexOf("```json");
        if (fenceStart < 0) fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int bodyStart = text.indexOf('\n', fenceStart);
            if (bodyStart >= 0) {
                int fenceEnd = text.indexOf("```", bodyStart + 1);
                if (fenceEnd >= 0) {
                    String inner = text.substring(bodyStart + 1, fenceEnd).trim();
                    if (!inner.isEmpty()) out.add(inner);
                }
            }
        }
        return out;
    }

    /**
     * Parses one candidate string into an {@link EngineAction} if it
     * is a JSON object with a known {@code type}. Returns {@code null}
     * for any failure — caller iterates over candidates.
     */
    private @Nullable EngineAction tryParseAction(String candidate) {
        String stripped = candidate.trim();
        if (stripped.isEmpty()) return null;
        if (!stripped.startsWith("{") || !stripped.endsWith("}")) return null;
        Map<String, Object> json;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(stripped, Map.class);
            json = parsed;
        } catch (RuntimeException e) {
            return null;
        }
        Object typeVal = json.get("type");
        if (!(typeVal instanceof String typeStr) || typeStr.isBlank()) return null;
        if (!supportedActionTypes().contains(typeStr)) return null;
        Object reasonVal = json.get("reason");
        // The reason is required for tool calls but we're already in
        // recovery; synthesise a placeholder rather than refuse and
        // leak the JSON to chat.
        String reasonStr = (reasonVal instanceof String rs && !rs.isBlank())
                ? rs
                : "recovered from free-text emission";
        Map<String, Object> params = new LinkedHashMap<>(json);
        params.remove("type");
        params.remove("reason");
        return new EngineAction(typeStr, reasonStr, params);
    }

    /**
     * Cleans a free-text fallback reply before it lands in the user's
     * chat:
     *
     * <ul>
     *   <li>Removes any embedded {@code <process-event …> … </process-event>}
     *       block — the LLM sometimes copies these from its drain
     *       context into its prose, which leaks structural plumbing to
     *       the user.</li>
     *   <li>Removes any embedded fenced {@code ```json …action-shape… ```}
     *       block — the LLM occasionally emits an action call as a
     *       code-fence inside prose instead of as a real tool call;
     *       the action-recovery path already extracts that path, the
     *       sanitizer just makes sure the literal JSON doesn't appear
     *       to the user when recovery couldn't dispatch it (e.g. when
     *       the JSON contained syntax errors).</li>
     *   <li>Collapses the resulting double-blank lines.</li>
     * </ul>
     *
     * <p>Conservative: when the cleaning would reduce the text to
     * essentially nothing (less than 20 characters), the original
     * text is returned — better to show plumbing than nothing.
     */
    static String sanitizeFallbackText(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        // Strip <process-event ...> ... </process-event> (case-insensitive,
        // multi-line). Pattern is forgiving so attribute order / quoting
        // variants don't matter.
        s = s.replaceAll("(?is)<process-event\\b[^>]*>.*?</process-event>", "");
        // Strip <peer-event ...> ... </peer-event> (Eddie-side cousin).
        s = s.replaceAll("(?is)<peer-event\\b[^>]*>.*?</peer-event>", "");
        // Strip <tool-result ...> ... </tool-result>.
        s = s.replaceAll("(?is)<tool-result\\b[^>]*>.*?</tool-result>", "");
        // Strip a fenced ```json { ... } ``` block whose body parses as
        // an action shape. We don't try every fence — only json-typed
        // ones with an action-like top-level object (heuristic on
        // \"type\" + \"reason\" markers).
        s = s.replaceAll(
                "(?is)```json\\s*\\{\\s*\"type\"\\s*:\\s*\"[A-Z_]+\"[\\s\\S]*?\\}\\s*```",
                "");
        // Collapse run-on blank lines from the cuts.
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        if (s.length() < 20 && text.length() > 20) {
            // Sanitization wiped the whole message — leave original
            // so the user at least sees something. Operator/Logs will
            // show the warning the caller already emitted.
            return text;
        }
        return s;
    }

    /**
     * Strip a leading/trailing markdown code fence (```json … ``` or
     * ``` … ```) from a free-text reply. Returns the original string
     * when no fence is present. Single pass — nested fences not
     * unwrapped (they wouldn't be valid JSON anyway).
     */
    private static String stripJsonCodeFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) return t;
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) return t;
        String body = t.substring(firstNl + 1);
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.trim();
    }

    private String invalidActionToolResult(@Nullable String error) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", error == null ? "invalid action" : error);
            err.put("hint", "Re-emit the action call with a valid 'type' (one of "
                    + supportedActionTypes()
                    + ") and a non-blank 'reason'. Type-specific fields must match "
                    + "the schema for the chosen type.");
            return objectMapper.writeValueAsString(err);
        } catch (RuntimeException e) {
            return "{\"error\":\"" + (error == null ? "invalid action" : error) + "\"}";
        }
    }

    private String semanticRejectToolResult(String hint) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "semantic_reject");
            err.put("hint", hint);
            return objectMapper.writeValueAsString(err);
        } catch (RuntimeException e) {
            return "{\"error\":\"semantic_reject\",\"hint\":\""
                    + hint.replace("\"", "'") + "\"}";
        }
    }

    /**
     * Subclass hook for post-parse semantic rejection of an otherwise
     * schema-valid action. Return {@code null} to accept the action;
     * return a non-null hint to force a correction iteration (the hint
     * is fed back to the LLM as the action tool's tool-result, exactly
     * like a schema error). After the correction budget is exhausted
     * the loop falls back to {@code bestFreeText} — same path as an
     * unrecoverable schema error — so the rejection hint never leaks
     * into the chat as the assistant's reply.
     *
     * <p>Used e.g. to forbid spawn actions on event-only turns without
     * the previous behaviour of emitting the engine-internal reject
     * string as a chat message.
     */
    protected @Nullable String validateActionSemantics(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        return null;
    }

    /**
     * Read-only tool dispatch: identical pattern to the engine-side
     * tool loop, but stripped of the action-call branch (handled
     * separately). Returns the serialised result plus a flag so the
     * caller can detect whether the call succeeded (used to suppress
     * a concurrent action that would otherwise hallucinate success).
     */
    private record ReadToolOutcome(String json, boolean failed) {}

    private ReadToolOutcome invokeReadTool(
            ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseToolArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("{} id='{}' read-tool='{}' bad arguments: {}",
                    name(), processId, call.name(), e.getMessage());
            return new ReadToolOutcome(
                    errorJson("Invalid tool arguments: " + e.getMessage()), true);
        }
        log.info("{} id='{}' read_tool {}({})",
                name(), processId, call.name(), summariseArgs(params));
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return new ReadToolOutcome(objectMapper.writeValueAsString(result), false);
        } catch (ToolException e) {
            log.info("{} id='{}' read-tool='{}' returned error: {}",
                    name(), processId, call.name(), e.getMessage());
            return new ReadToolOutcome(errorJson(e.getMessage()), true);
        } catch (RuntimeException e) {
            log.warn("{} id='{}' read-tool='{}' unexpected failure: {}",
                    name(), processId, call.name(), e.toString());
            return new ReadToolOutcome(
                    errorJson("Tool failed: " + e.getMessage()), true);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(raw, Map.class);
    }

    private String errorJson(String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", message);
            return objectMapper.writeValueAsString(err);
        } catch (RuntimeException e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    private static String summariseArgs(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=");
            String v = String.valueOf(e.getValue());
            if (v.length() > 80) v = v.substring(0, 77) + "...";
            sb.append(v.replace("\n", "\\n"));
        }
        return sb.toString();
    }

    private static String summarise(String s) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return oneLine.length() > 100 ? oneLine.substring(0, 97) + "..." : oneLine;
    }

    /**
     * System message injected when the LLM emits free text without
     * any tool call. Sprach-agnostisch, structural — talks about
     * the action contract, not about specific phrases.
     */
    protected String noActionCorrection() {
        return "VALIDATION CHECK: your previous response had no tool call. "
                + "Every turn must end with exactly one call to the `"
                + actionToolName() + "` tool. The action's `type` must be "
                + "one of " + supportedActionTypes() + " and its `reason` "
                + "must be a non-blank explanation of why this action was "
                + "chosen. If your previous text was a complete answer to "
                + "the user, re-emit it as `" + actionToolName() + "` with "
                + "type='ANSWER' and message=<that text VERBATIM>. Free "
                + "assistant text without a tool call is never the right "
                + "output.";
    }

    // ─────────────────────────────────────────────
    // Streaming primitive (shared with subclasses)
    // ─────────────────────────────────────────────

    /**
     * Runs one streaming LLM call and returns the complete
     * {@link AiMessage}. Tokens stream through the
     * {@link ChunkBatcher} into the engine's chat-stream channel
     * the same way as the legacy tool-loop, so clients render
     * incremental progress without changes.
     *
     * <p>Throws {@link AiChatException} on stream failure (typically
     * after the resilient-retry budget is exhausted in the underlying
     * {@code ChatBehavior}). Callers may catch and recover with the
     * best-free-text fallback pattern.
     */
    protected AiMessage streamOneIteration(
            AiChat aiChat,
            ChatRequest request,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            String modelAlias) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ClientEventPublisher events = ctx.events();
        String sessionId = process.getSessionId();
        long startMs = System.currentTimeMillis();

        ChunkBatcher batcher = new ChunkBatcher(
                streamingProperties.getChunkCharThreshold(),
                streamingProperties.getChunkFlushMs(),
                chunk -> {
                    ChatMessageChunkData data = ChatMessageChunkData.builder()
                            .thinkProcessId(process.getId())
                            .processName(process.getName())
                            .role(ChatRole.ASSISTANT)
                            .chunk(chunk)
                            .build();
                    events.publish(sessionId,
                            MessageType.CHAT_MESSAGE_STREAM_CHUNK, data);
                });

        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) return;
                try {
                    batcher.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("{} chunk-publish threw: {}", name(), e.toString());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                batcher.flush();
                done.complete(complete);
            }

            @Override
            public void onError(Throwable error) {
                batcher.flush();
                done.completeExceptionally(error);
            }
        });

        try {
            ChatResponse complete = done.get();
            llmCallTracker.record(
                    process, request, complete, System.currentTimeMillis() - startMs, modelAlias);
            return complete.aiMessage();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException(
                    name() + " streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException(name() + " streaming interrupted", e);
        }
    }
}
