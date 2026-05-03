package de.mhus.vance.brain.arthur;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.tools.context.RespondTool;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Arthur — the reactive session-chat engine and reference
 * implementation of the orchestrator pattern. Listens, delegates,
 * synthesises. See {@code specification/arthur-engine.md}.
 *
 * <p><b>Reactive.</b> Arthur never reaches {@code DONE}. Each turn
 * starts when something hits the inbox (user input, sibling
 * {@code ProcessEvent}, async {@code ToolResult}) and ends with the
 * engine returning to {@code READY} or {@code BLOCKED}.
 *
 * <p><b>Drain-once-per-turn.</b> Arthur overrides
 * {@link #runTurn} so the entire inbox is folded into a single LLM
 * round-trip — five queued events are one LLM call, not five.
 *
 * <p><b>Tool pool (restricted).</b> Arthur sees only process control
 * + bundled docs. Filesystem, shell, web, MCP — those belong to
 * workers spawned via {@code process_create}. {@link #allowedTools}
 * publishes the set; the runtime enforces it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArthurEngine implements ThinkEngine {

    public static final String NAME = "arthur";
    public static final String VERSION = "0.1.0";

    public static final String GREETING =
            "Hi, I'm Arthur. What are we working on?";

    /**
     * Bare-minimum fallback when no recipe override is in play —
     * basically never used in production because the bundled
     * {@code arthur} recipe always supplies the real prompt. Kept
     * tiny on purpose so a misconfigured spawn still produces a
     * coherent (if generic) bot rather than an unprompted LLM.
     */
    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Arthur, the chat agent of a Vance session. "
                    + "Delegate operational work to workers via process_create; "
                    + "synthesise their replies for the user.";

    /**
     * Cached final system prompt (engine-default base + bundled-recipe
     * catalog). Composed once after first turn since bundled-recipe
     * state doesn't change without a brain restart.
     */

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "respond",
            "process_create",
            "process_steer",
            "process_stop",
            "process_pause",
            "process_resume",
            "process_list",
            "process_status",
            "recipe_list",
            "recipe_describe",
            "manual_list",
            "manual_read",
            "inbox_post");

    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    // ──────────────────── End-of-turn marker ────────────────────
    // The structured {@link RespondTool} is the explicit end-of-turn
    // signal: the model emits `respond(message, awaiting_user_input)`
    // and the tool-loop returns the message + the awaiting flag. The
    // legacy "intent-without-action" regex validator (sprach-bias on
    // EN/DE phrases like "I'll check" / "Soll ich") has been retired
    // in favour of this structured signal — see
    // specification/structured-engine-output.md.

    /** Max corrections per turn for the structural no-tool-call validator. */
    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    /** Max iterations of the format-correction sub-loop. */
    private static final int MAX_FORMAT_CORRECTION_ITERS = 2;

    /**
     * Mini-system-prompt for the format-correction sub-loop. See
     * Ford.FORMAT_CORRECTION_SYSTEM for the full rationale — same
     * pattern: fresh sub-conversation, only `respond` available, no
     * fallback into "answer the question again" territory.
     */
    private static final String FORMAT_CORRECTION_SYSTEM =
            "You are a format-correction agent. Your only job is to "
                    + "wrap the assistant text below into a `respond` "
                    + "tool call.\n\n"
                    + "Rules:\n"
                    + "- Emit exactly one tool call: "
                    + "`respond(message=<the text VERBATIM>, awaiting_user_input=true)`.\n"
                    + "- Do NOT modify, summarise, shorten, translate, or rewrite the text.\n"
                    + "- Do NOT add any commentary outside the tool call.\n"
                    + "- Do NOT call any other tool.\n"
                    + "- The text you must wrap is the assistant message that follows.";

    /**
     * Language-agnostic correction for "model produced free text with
     * no tool call". Replaces the old regex-based intent-without-action
     * validator: the structural check is whether any tool was emitted,
     * not what phrases the text contains.
     */
    private static final String NO_TOOL_CALL_CORRECTION =
            "VALIDATION CHECK: your previous response had no tool call. "
                    + "Every turn must end with at least one tool call: "
                    + "the work tools first (process_create, process_steer, …), "
                    + "then `respond(message=..., awaiting_user_input=...)` "
                    + "as the final marker.\n\n"
                    + "If your previous response was a complete answer to "
                    + "the user (any non-trivial assistant text), re-emit "
                    + "that text VERBATIM — same wording, same length, "
                    + "same formatting — as the `message` argument of "
                    + "`respond`. Do not summarise it, do not shorten it, "
                    + "do not replace it with a brief acknowledgement. "
                    + "The text you already wrote IS the answer; just wrap "
                    + "it in `respond(message=<that text>, awaiting_user_input=true)`.\n\n"
                    + "If your previous response was an intent-to-act ("
                    + "\"I will check…\"), emit the action tool now "
                    + "instead. Free assistant text without a tool call "
                    + "is never the right output.";

    /**
     * Base cascade path for the Arthur engine prompt. Loaded via
     * {@link de.mhus.vance.brain.thinkengine.EnginePromptResolver#resolveTiered};
     * SMALL models automatically pick up
     * {@code prompts/arthur-prompt-small.md} when one exists, otherwise
     * fall through to this base path. Tenants override either variant by
     * placing matching files in their {@code _vance} (or per-user) project.
     * Recipes can swap the paths via {@code promptDocument} /
     * {@code promptDocumentSmall} params.
     */
    private static final String DEFAULT_PROMPT_PATH = "prompts/arthur-prompt.md";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ArthurProperties arthurProperties;
    private final RecipeLoader recipeLoader;
    private final ModelCatalog modelCatalog;
    private final LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Arthur (Session Chat)";
    }

    @Override
    public String description() {
        return "Reactive session-chat agent. Talks to the user, "
                + "delegates deep work to worker processes, "
                + "synthesises their results back into the chat.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(GREETING)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Arthur.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Arthur.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    /**
     * Single-message entry — used when the runtime delivers a
     * message via the per-message default. Arthur prefers
     * {@link #runTurn} (batch), but if something does call
     * {@code steer} directly we treat it as a one-element inbox.
     */
    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurnFor(process, ctx, List.of(message));
    }

    /**
     * Drains the inbox and processes everything in one LLM
     * round-trip. Auto-Wakeup: keep re-draining until the queue
     * stays empty across a full pass — covers messages that arrive
     * during the LLM call.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        while (true) {
            // Cooperative halt-check between drain iterations. The
            // pause-task on the lane is queued *behind* this runTurn
            // — without yielding here, a busy drain-loop would keep
            // chewing through pendings and the queued status-PAUSED
            // task would never get to fire. See
            // ThinkProcessDocument.haltRequested.
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Arthur.runTurn id='{}' — halt requested, yielding",
                        process.getId());
                return;
            }
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                return;
            }
            runTurnFor(process, ctx, drained);
        }
    }

    // ──────────────────── One turn ────────────────────

    private void runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        // ─── Routing-Schicht: auto-forward to the active delegated worker
        // when the user is just answering an outstanding clarification.
        // Bypasses the LLM round-trip entirely so worker-name halluci-
        // nation is impossible. Falls through to the normal LLM turn
        // when the conditions don't match (mixed inbox, no active
        // delegation, worker no longer BLOCKED, etc.).
        if (tryAutoForwardToDelegatedWorker(process, ctx, inbox)) {
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Default IDLE on any abnormal exit — matches legacy lifecycle.
        // Reset to outcome.awaitingUserInput() inside the try.
        boolean awaitingUserInput = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();

            // Persist user-typed messages into the chat log (so future turns
            // see them in history). Other inbox kinds — ProcessEvent,
            // ToolResult, ExternalCommand — are turn-local: they steer this
            // turn but don't get written back as user-visible chat history.
            StringBuilder userTextForTriggers = new StringBuilder();
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                    if (uci.content() != null && !uci.content().isBlank()) {
                        if (userTextForTriggers.length() > 0) userTextForTriggers.append('\n');
                        userTextForTriggers.append(uci.content());
                    }
                }
            }

            // Skill auto-trigger: match the joined user-input text against
            // PATTERN/KEYWORDS triggers of visible skills, one-shot
            // activate matches. Filters via process.allowedSkillsOverride.
            if (userTextForTriggers.length() > 0) {
                skillTriggerMatcher.detectAndActivate(process, userTextForTriggers.toString());
            }

            // Build the chat with primary + ordered fallback chain plus
            // the standard resilience-notifier and (when tracing.llm is
            // on) LLM-trace persistence. EngineChatFactory handles the
            // boilerplate that used to sit here — single-entry behaviour
            // is unchanged when params.fallbackModels is empty / missing.
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();
            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    config.provider(), config.modelName());
            // params.modelSize: SMALL/LARGE force the prompt variant
            // independently of the catalog; AUTO/missing falls back
            // to the catalog's classification.
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());

            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, inbox, effectiveSize);
            int maxIters = paramInt(process, "maxIterations",
                    arthurProperties.getMaxToolIterations());
            boolean validation = paramBool(process, "validation", false);
            log.debug("Arthur.turn id='{}' inbox={} historyMsgs={} model={} maxIters={} validation={}",
                    process.getId(), inbox.size(), messages.size(),
                    config.modelName(), maxIters, validation);

            String modelAlias = config.provider() + ":" + config.modelName();
            TurnOutcome outcome = runToolLoop(
                    aiChat, toolSpecs, tools, messages, ctx, process,
                    maxIters, validation, modelAlias);
            if (outcome.needsFormatCorrection()) {
                outcome = runFormatCorrectionLoop(
                        aiChat, toolSpecs, process, outcome.finalText(), modelAlias, ctx);
            }
            awaitingUserInput = outcome.awaitingUserInput();
            String finalText = outcome.finalText();

            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Arthur.turn id='{}' awaiting={} -> '{}'",
                    process.getId(), awaitingUserInput, preview);

            // Delegation-pointer maintenance: the LLM-mediated turn
            // already happened, so we always start by clearing — a
            // fresh decision was made. If this turn relayed exactly
            // one BLOCKED ProcessEvent and ended awaiting user input,
            // re-arm the pointer so the user's next reply auto-routes
            // to the worker without an LLM round-trip.
            updateDelegationPointer(process, inbox, awaitingUserInput);
        } finally {
            ThinkProcessStatus exitStatus = awaitingUserInput
                    ? ThinkProcessStatus.BLOCKED
                    : ThinkProcessStatus.IDLE;
            thinkProcessService.updateStatus(process.getId(), exitStatus);
        }
    }

    /**
     * Auto-forwards user-chat-input to the currently-delegated worker
     * when the conditions match — see
     * {@link ThinkProcessDocument#getActiveDelegationWorkerId()} for
     * the lifecycle. Returns {@code true} when forwarding happened
     * (caller must skip the LLM turn).
     *
     * <p>Eligibility (all required):
     * <ul>
     *   <li>{@code activeDelegationWorkerId} is set on this process</li>
     *   <li>The inbox contains only {@link SteerMessage.UserChatInput}
     *       messages — anything else (ProcessEvent, ToolResult, …)
     *       represents new state the LLM should reason about</li>
     *   <li>The pointed worker still exists in the same tenant +
     *       session and is in {@link ThinkProcessStatus#BLOCKED} —
     *       a worker that finished or was stopped is no target</li>
     * </ul>
     */
    private boolean tryAutoForwardToDelegatedWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {
        String workerId = process.getActiveDelegationWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        if (inbox.isEmpty()) {
            return false;
        }
        StringBuilder combined = new StringBuilder();
        for (SteerMessage m : inbox) {
            if (!(m instanceof SteerMessage.UserChatInput uci)) {
                return false;
            }
            if (uci.content() == null || uci.content().isBlank()) {
                continue;
            }
            if (combined.length() > 0) combined.append('\n');
            combined.append(uci.content());
        }
        if (combined.length() == 0) {
            return false;
        }
        Optional<ThinkProcessDocument> targetOpt =
                thinkProcessService.findById(workerId);
        if (targetOpt.isEmpty()) {
            log.info(
                    "Arthur id='{}' delegation pointer references missing worker '{}' — clearing and falling through to LLM",
                    process.getId(), workerId);
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }
        ThinkProcessDocument target = targetOpt.get();
        if (!process.getTenantId().equals(target.getTenantId())
                || !process.getSessionId().equals(target.getSessionId())) {
            log.warn(
                    "Arthur id='{}' delegation pointer crosses scope (tenant/session mismatch) — clearing",
                    process.getId());
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }
        if (target.getStatus() != ThinkProcessStatus.BLOCKED) {
            log.info(
                    "Arthur id='{}' delegation target '{}' is {} (not BLOCKED) — clearing pointer, falling through to LLM",
                    process.getId(), target.getName(), target.getStatus());
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }

        // Persist the user's text into Arthur's chat log so the
        // history reflects the (passive-relay) round-trip.
        ChatMessageService chatLog = ctx.chatMessageService();
        chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.USER)
                .content(combined.toString())
                .build());

        // Forward to the worker via the message router. The worker's
        // lane wakes itself; we don't block on the worker's turn —
        // the worker's reply (BLOCKED/DONE event) lands in our inbox
        // through ParentNotificationListener and triggers the next
        // (LLM-mediated) Arthur turn.
        messageRouter.dispatch(
                process.getId(),
                target.getId(),
                de.mhus.vance.shared.thinkprocess.PendingMessageDocument.builder()
                        .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                        .at(java.time.Instant.now())
                        .fromUser("auto-route:" + process.getId())
                        .content(combined.toString())
                        .build());

        log.info(
                "Arthur id='{}' auto-forwarded {} chars to delegated worker '{}' (id='{}')",
                process.getId(), combined.length(), target.getName(), target.getId());

        // Status: stay BLOCKED — Arthur is still waiting (just for
        // the worker now instead of the user). When the worker comes
        // back via ProcessEvent, the next turn runs normally.
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        return true;
    }

    /**
     * Sets / clears {@link ThinkProcessDocument#activeDelegationWorkerId}
     * based on what just happened. The simple rule: pointer survives
     * iff this turn relayed exactly one BLOCKED ProcessEvent and ended
     * awaiting the user — i.e. Arthur is forwarding a worker's
     * clarification question. Any other outcome clears the pointer.
     */
    private void updateDelegationPointer(
            ThinkProcessDocument process,
            List<SteerMessage> inbox,
            boolean awaitingUserInput) {
        String currentWorker = process.getActiveDelegationWorkerId();
        String nextWorker = null;
        if (awaitingUserInput) {
            String single = singleBlockedSourceProcessId(inbox);
            if (single != null) {
                nextWorker = single;
            }
        }
        if (java.util.Objects.equals(currentWorker, nextWorker)) {
            return;
        }
        thinkProcessService.updateActiveDelegationWorkerId(process.getId(), nextWorker);
        if (nextWorker != null) {
            log.info("Arthur id='{}' delegation pointer set → worker id='{}'",
                    process.getId(), nextWorker);
        } else if (currentWorker != null) {
            log.info("Arthur id='{}' delegation pointer cleared", process.getId());
        }
    }

    /**
     * Returns the {@code sourceProcessId} when the inbox contained
     * exactly one BLOCKED-typed {@link SteerMessage.ProcessEvent},
     * otherwise {@code null}. Multi-clarification fan-in stays
     * LLM-mediated — disambiguating two simultaneous worker questions
     * is a planning decision, not a routing one.
     */
    private static @Nullable String singleBlockedSourceProcessId(List<SteerMessage> inbox) {
        String found = null;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.ProcessEvent pe
                    && pe.type() == de.mhus.vance.api.thinkprocess.ProcessEventType.BLOCKED) {
                if (found != null) {
                    return null;
                }
                found = pe.sourceProcessId();
            }
        }
        return found;
    }

    /**
     * Outcome of one tool-loop turn — final assistant text plus the
     * explicit {@code awaiting_user_input} flag that drives the
     * post-turn process status (BLOCKED vs IDLE).
     */
    private record TurnOutcome(
            String finalText,
            boolean awaitingUserInput,
            /** See Ford.TurnOutcome.needsFormatCorrection — same semantics. */
            boolean needsFormatCorrection) {}

    /** Parsed payload of a {@link RespondTool} call. */
    private record RespondArgs(String message, boolean awaitingUserInput) {}

    /**
     * Tool-call loop in streaming mode. Same shape as Ford's. The
     * {@link RespondTool} is the end-of-turn marker: when the model
     * emits a {@code respond} call, the loop dispatches any other
     * tool calls in the same response (so the audit trail keeps the
     * results) and then returns the {@code respond} args as
     * {@link TurnOutcome}.
     *
     * <p>Fallback: a response without any tool calls (model ignored
     * the convention) hands back the streamed text with
     * {@code awaitingUserInput=false}, preserving the legacy IDLE
     * lifecycle.
     */
    private TurnOutcome runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            int maxIters,
            boolean validation,
            String modelAlias) {
        StringBuilder finalText = new StringBuilder();
        int corrections = 0;
        // Best Free-Text seen so far. Used as last-resort
        // `respond.message` when the LLM collapses or maxIters runs
        // out — preserves the work instead of forcing a respawn from
        // scratch. Same pattern as Ford.runToolLoop.
        String bestFreeText = "";
        for (int iter = 0; iter < maxIters; iter++) {
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }
            AiMessage reply;
            try {
                reply = streamOneIteration(aiChat, req.build(), ctx, process, modelAlias);
            } catch (RuntimeException e) {
                if (!bestFreeText.isEmpty()) {
                    log.warn(
                            "Arthur id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text seen ({} chars), deferring to format-correction",
                            process.getId(), e.toString(), bestFreeText.length());
                    return new TurnOutcome(bestFreeText, true, /*needsFormatCorrection*/ true);
                }
                log.warn("Arthur id='{}' tool-loop LLM failure with no recoverable text",
                        process.getId());
                throw e;
            }

            String replyText = reply.text();
            if (replyText != null && replyText.length() > bestFreeText.length()) {
                bestFreeText = replyText;
            }

            if (!reply.hasToolExecutionRequests()) {
                // Model emitted free text with no tool call. The reply
                // IS the answer; only the wrapping `respond` is missing.
                // Defer to the format-correction sub-loop instead of
                // polluting the main conversation with format nudges.
                String text = reply.text();
                if (text != null) {
                    finalText.append(text);
                }
                return new TurnOutcome(
                        finalText.toString(), true, /*needsFormatCorrection*/ true);
            }

            ToolExecutionRequest respondCall = null;
            List<ToolExecutionRequest> others = new ArrayList<>();
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                if (RespondTool.NAME.equals(call.name())) {
                    if (respondCall == null) {
                        respondCall = call;
                    }
                } else {
                    others.add(call);
                }
            }

            messages.add(reply);
            for (ToolExecutionRequest call : others) {
                String result = invokeOne(tools, call, process.getId());
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
            if (respondCall != null) {
                if (!others.isEmpty()) {
                    // Model emitted respond *together with* work tools.
                    // The respond payload was speculative — it didn't
                    // see the tool results yet. Synthesise a tool-result
                    // rejection for respond and continue the loop so the
                    // next turn produces the real synthesised answer.
                    log.info(
                            "Arthur id='{}' rejecting premature respond emitted alongside {} other tool(s) — looping for actual results",
                            process.getId(), others.size());
                    messages.add(ToolExecutionResultMessage.from(respondCall,
                            "{\"error\":\"respond was called together with other tool calls. "
                                    + "respond must be the LAST and ONLY tool call in a turn, "
                                    + "after you have observed the results of all work tools. "
                                    + "Continue the loop: read the tool results above, then "
                                    + "call respond with the synthesised answer.\"}"));
                    continue;
                }
                RespondArgs args = parseRespondArgs(respondCall);
                if (args.message() != null && !args.message().isEmpty()) {
                    finalText.append(args.message());
                }
                return new TurnOutcome(
                        finalText.toString(), args.awaitingUserInput(),
                        /*needsFormatCorrection*/ false);
            }
        }
        if (!bestFreeText.isEmpty()) {
            log.warn(
                    "Arthur id='{}' exceeded {} tool iterations — recovering with best Free-Text seen ({} chars), deferring to format-correction",
                    process.getId(), maxIters, bestFreeText.length());
            return new TurnOutcome(bestFreeText, true, /*needsFormatCorrection*/ true);
        }
        throw new AiChatException(
                "Arthur exceeded " + maxIters
                        + " tool iterations — no recoverable text, aborting turn.");
    }

    /**
     * Format-correction sub-loop. See Ford.runFormatCorrectionLoop —
     * same pattern: fresh sub-conversation, only `respond` available,
     * falls back to Free-Text-verbatim if the model still doesn't
     * emit `respond` after {@link #MAX_FORMAT_CORRECTION_ITERS}.
     */
    private TurnOutcome runFormatCorrectionLoop(
            AiChat aiChat,
            List<ToolSpecification> mainToolSpecs,
            ThinkProcessDocument process,
            String freeText,
            String modelAlias,
            ThinkEngineContext ctx) {
        if (freeText == null || freeText.isBlank()) {
            return new TurnOutcome("", true, /*needsFormatCorrection*/ false);
        }
        log.info(
                "Arthur id='{}' format-correction sub-loop starting (text {} chars)",
                process.getId(), freeText.length());

        // Gemini-compatible: end on UserMessage. Wrap-instruction +
        // text-to-wrap go into the same UserMessage.
        List<ChatMessage> sub = new ArrayList<>();
        sub.add(SystemMessage.from(FORMAT_CORRECTION_SYSTEM));
        sub.add(UserMessage.from(
                "Wrap the following assistant text VERBATIM in a "
                        + "`respond(message=<that text>, awaiting_user_input=true)` "
                        + "tool call. Do not modify, summarise, or shorten the text. "
                        + "Emit only the tool call, no other text.\n\n"
                        + "--- BEGIN ASSISTANT TEXT ---\n"
                        + freeText
                        + "\n--- END ASSISTANT TEXT ---"));

        List<ToolSpecification> respondOnly = new ArrayList<>();
        for (ToolSpecification spec : mainToolSpecs) {
            if (RespondTool.NAME.equals(spec.name())) {
                respondOnly.add(spec);
                break;
            }
        }
        if (respondOnly.isEmpty()) {
            log.warn(
                    "Arthur id='{}' format-correction: respond tool not in spec list — fallback to verbatim",
                    process.getId());
            return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
        }

        for (int iter = 0; iter < MAX_FORMAT_CORRECTION_ITERS; iter++) {
            ChatRequest req = ChatRequest.builder()
                    .messages(sub)
                    .toolSpecifications(respondOnly)
                    .build();
            AiMessage reply;
            try {
                reply = streamOneIteration(aiChat, req, ctx, process, modelAlias);
            } catch (RuntimeException e) {
                log.warn(
                        "Arthur id='{}' format-correction LLM failure ({}) — falling back to Free-Text verbatim",
                        process.getId(), e.toString());
                return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
            }

            if (reply.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                    if (RespondTool.NAME.equals(call.name())) {
                        RespondArgs args = parseRespondArgs(call);
                        String message = args.message() != null && !args.message().isBlank()
                                ? args.message()
                                : freeText;
                        log.info(
                                "Arthur id='{}' format-correction succeeded after {} iter — wrapped {} chars",
                                process.getId(), iter + 1, message.length());
                        return new TurnOutcome(
                                message, args.awaitingUserInput(),
                                /*needsFormatCorrection*/ false);
                    }
                }
            }

            sub.add(reply);
            sub.add(UserMessage.from(
                    "You did not call `respond`. Call it now with the "
                            + "assistant text from my first message as the "
                            + "`message` argument. Emit only the tool call, "
                            + "nothing else."));
        }

        log.warn(
                "Arthur id='{}' format-correction exhausted {} iters — falling back to Free-Text verbatim",
                process.getId(), MAX_FORMAT_CORRECTION_ITERS);
        return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
    }

    private RespondArgs parseRespondArgs(ToolExecutionRequest call) {
        String raw = call.arguments();
        if (raw == null || raw.isBlank()) {
            return new RespondArgs("", true);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            Object msgVal = parsed.get(RespondTool.PARAM_MESSAGE);
            Object awaitingVal = parsed.get(RespondTool.PARAM_AWAITING_USER_INPUT);
            String message = msgVal instanceof String s ? s : "";
            boolean awaiting = !(awaitingVal instanceof Boolean b) || b;
            return new RespondArgs(message, awaiting);
        } catch (RuntimeException e) {
            log.warn("Arthur: failed to parse respond args (raw='{}'): {}", raw, e.toString());
            return new RespondArgs("", true);
        }
    }

    private AiMessage streamOneIteration(
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
                    events.publish(sessionId, MessageType.CHAT_MESSAGE_STREAM_CHUNK, data);
                });

        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) return;
                try {
                    batcher.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("Arthur chunk-publish threw: {}", e.toString());
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
                    process, complete, System.currentTimeMillis() - startMs, modelAlias);
            return complete.aiMessage();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Arthur streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Arthur streaming interrupted", e);
        }
    }

    private String invokeOne(
            ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        log.info("Arthur id='{}' tool_use {}({})",
                processId, call.name(), summarizeArgs(params));
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Arthur id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' tool='{}' unexpected failure: {}",
                    processId, call.name(), e.toString());
            return errorJson("Tool failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(raw, Map.class);
    }

    /**
     * One-line projection of tool args for the log — keeps secrets and
     * giant payloads out without losing the "what was called with what"
     * signal that makes hangs diagnosable.
     */
    private static String summarizeArgs(Map<String, Object> params) {
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

    private String errorJson(String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", message);
            return objectMapper.writeValueAsString(err);
        } catch (RuntimeException e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    // ──────────────────── Prompt building ────────────────────

    /**
     * Builds the LLM input: system prompt → chat history (assistant +
     * past user messages) → current-turn inbox rendered as user-role
     * messages with the {@code <process-event>} XML wrapper for
     * non-user inputs.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox,
            ModelSize modelSize) {
        List<ChatMessage> messages = new ArrayList<>();
        String base = SystemPrompts.compose(process,
                engineDefaultPrompt(process, modelSize), modelSize);
        String withCatalog = base + buildRecipeCatalogSection(process);
        // Append the project-memory block (memory.* settings cascade) so
        // the user can pin language / tone / persona / arbitrary key:value
        // hints at any scope without rewriting the recipe prompt.
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            withCatalog = withCatalog + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(withCatalog));

        // Active history (ARCHIVED_CHAT compaction-aware once we wire
        // memoryService — for v1 just use full active history).
        List<ChatMessageDocument> history = chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId());

        // The inbox messages we just persisted (UserChatInput) are
        // already in `history`. Render the rest separately so the LLM
        // sees them clearly tagged.
        int userInputCount = 0;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput) userInputCount++;
        }

        // History up to the just-appended user inputs is the "old"
        // conversation. The trailing N user messages are the current
        // turn — they're already represented as USER chat messages.
        for (ChatMessageDocument msg : history) {
            messages.add(toLangchain(msg));
        }

        // Now append the non-user inbox items (events, tool results,
        // external commands) as user-role messages with the XML
        // wrapper Arthur's prompt is trained on.
        for (SteerMessage m : inbox) {
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }

        return messages;
    }

    /**
     * Looks up the process name for a {@code ProcessEvent}'s source id —
     * used so {@code <process-event>} markers carry the human-readable
     * name the LLM should pass to {@code process_steer / process_stop /
     * etc.} instead of the Mongo id.
     */
    private @Nullable String lookupProcessName(@Nullable String processId) {
        if (processId == null || processId.isBlank()) {
            return null;
        }
        return thinkProcessService.findById(processId)
                .map(ThinkProcessDocument::getName)
                .orElse(null);
    }

    /** Rendering helper that needs access to the {@code thinkProcessService}. */
    private String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\"");
            String sourceName = lookupProcessName(pe.sourceProcessId());
            if (sourceName != null && !sourceName.isBlank()) {
                sb.append(" sourceProcessName=\"")
                        .append(escapeAttr(sourceName))
                        .append("\"");
            }
            sb.append(" type=\"").append(pe.type().name().toLowerCase()).append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        return renderStaticForLlm(m);
    }

    /**
     * Returns the LLM-facing rendering of one inbox message, or
     * {@code null} if the message has no separate rendering (the
     * UserChatInput case — already in chat history). Mirrors the
     * {@code <task-notification>} convention from Claude Code.
     */
    /**
     * Static rendering fallback for non-ProcessEvent messages. The
     * instance method {@link #renderForLlm(SteerMessage)} dispatches
     * here for everything that doesn't need the process-name lookup.
     */
    private static String renderStaticForLlm(SteerMessage m) {
        return switch (m) {
            case SteerMessage.UserChatInput uci -> null; // already in chat history
            case SteerMessage.ProcessEvent pe -> {
                // Should never hit this branch — the instance overload
                // above handles ProcessEvent. Keep for type-completeness.
                StringBuilder sb = new StringBuilder();
                sb.append("<process-event sourceProcessId=\"")
                        .append(escapeAttr(pe.sourceProcessId()))
                        .append("\" type=\"")
                        .append(pe.type().name().toLowerCase())
                        .append("\">");
                if (pe.humanSummary() != null) {
                    sb.append(escapeText(pe.humanSummary()));
                }
                sb.append("</process-event>");
                yield sb.toString();
            }
            case SteerMessage.ToolResult tr -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<tool-result toolCallId=\"")
                        .append(escapeAttr(tr.toolCallId()))
                        .append("\" toolName=\"")
                        .append(escapeAttr(tr.toolName()))
                        .append("\" status=\"")
                        .append(tr.status().name().toLowerCase())
                        .append("\">");
                if (tr.error() != null) {
                    sb.append("error: ").append(escapeText(tr.error()));
                } else if (tr.result() != null) {
                    sb.append(escapeText(tr.result().toString()));
                }
                sb.append("</tool-result>");
                yield sb.toString();
            }
            case SteerMessage.ExternalCommand ec -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<external-command name=\"")
                        .append(escapeAttr(ec.command()))
                        .append("\">")
                        .append(escapeText(String.valueOf(ec.params())))
                        .append("</external-command>");
                yield sb.toString();
            }
            case SteerMessage.InboxAnswer ia -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<inbox-answer itemId=\"")
                        .append(escapeAttr(ia.inboxItemId()))
                        .append("\" type=\"")
                        .append(ia.itemType().name().toLowerCase())
                        .append("\" outcome=\"")
                        .append(ia.answer().getOutcome().name().toLowerCase())
                        .append("\">");
                if (ia.answer().getReason() != null) {
                    sb.append("reason: ").append(escapeText(ia.answer().getReason()));
                } else if (ia.answer().getValue() != null) {
                    sb.append(escapeText(String.valueOf(ia.answer().getValue())));
                }
                sb.append("</inbox-answer>");
                yield sb.toString();
            }
            case SteerMessage.PeerEvent pe -> {
                // PeerEvents only ever reach Arthur when she's
                // acting as the chat-machinery for the Eddie hub
                // engine (delegate target). Render them so the
                // LLM sees what a peer hub did and can react
                // appropriately. Other engines (Marvin, Vogon)
                // never receive PeerEvents — their switch-cases
                // stay no-op.
                StringBuilder sb = new StringBuilder();
                sb.append("<peer-event sourceEddieProcessId=\"")
                        .append(escapeAttr(pe.sourceEddieProcessId()))
                        .append("\" type=\"")
                        .append(pe.type().name().toLowerCase())
                        .append("\">")
                        .append(escapeText(pe.humanSummary()))
                        .append("</peer-event>");
                yield sb.toString();
            }
        };
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String escapeText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Engine-default base — used by {@link SystemPrompts#compose}
     * when the process has no recipe override (rare; the bundled
     * {@code arthur} recipe normally supplies the rich content).
     * Recipe content always wins via APPEND/OVERWRITE; the
     * bundled-recipe catalog is appended <em>after</em> compose so it
     * sits at the end of the system prompt regardless.
     */
    /**
     * Resolves the engine-default prompt for the current turn through the
     * tier-aware document cascade. Recipe params {@code promptDocument}
     * (base path) and {@code promptDocumentSmall} (optional explicit
     * small-variant path) override the engine defaults; the resolver
     * automatically derives a {@code -small} suffix and falls through to
     * the base path when no small variant exists, so engines don't need
     * to maintain two separate files unless they want differentiated
     * tiers.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process, ModelSize modelSize) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        String smallOverride = paramString(process, "promptDocumentSmall", null);
        return enginePromptResolver.resolveTiered(
                process, basePath, smallOverride, modelSize, ENGINE_FALLBACK_PROMPT);
    }

    /**
     * Builds the bullet-list of recipes that gets appended to the
     * system prompt. Pulled fresh per call from
     * {@link RecipeLoader#listAll} so tenant- and project-recipes are
     * included alongside the bundled defaults — same source as
     * {@code recipe_list} at runtime.
     */
    private String buildRecipeCatalogSection(ThinkProcessDocument process) {
        // No projectId on a ThinkProcessDocument — RecipeLoader falls back
        // to the _vance system project, which is what Arthur wants here:
        // the catalog in the system prompt covers tenant-wide + bundled
        // recipes. Project-scoped recipes show up at runtime through
        // recipe_list (which has the full ToolInvocationContext).
        java.util.List<ResolvedRecipe> recipes = recipeLoader.listAll(
                process.getTenantId(), null);
        if (recipes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available worker recipes\n\n")
                .append("Use one of these names for `process_create(recipe=…)`. "
                        + "Call `recipe_list` at runtime if you want the live catalog.\n\n");
        for (ResolvedRecipe r : recipes) {
            sb.append("- `").append(r.name()).append("` — ")
                    .append(oneLine(r.description())).append("\n");
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String trimmed = s.trim().replace("\n", " ").replaceAll("\\s+", " ");
        return trimmed;
    }

    // ──────────────────── Config resolve (mirrors Ford) ────────────────────

    private static AiChatConfig resolveAiConfig(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver modelResolver) {
        String tenantId = process.getTenantId();
        // params.model wins (recipe alias or direct provider:model).
        // params.provider is honoured for backward-compat: if it's
        // set and params.model contains no colon, we synthesise
        // "<provider>:<model>". Otherwise we fall through to the
        // resolver's own default-handling.
        String paramModel = paramString(process, "model", null);
        String paramProvider = paramString(process, "provider", null);
        String spec;
        if (paramModel != null && paramModel.contains(":")) {
            spec = paramModel;
        } else if (paramModel != null && paramProvider != null) {
            spec = paramProvider + ":" + paramModel;
        } else if (paramModel != null) {
            // Bare model name with no colon and no separate provider —
            // assume it's an alias key in the default namespace, which
            // also covers the legacy "claude-sonnet-4-5" style by
            // routing through the alias map.
            spec = "default:" + paramModel;
        } else {
            spec = null; // resolver picks tenant default
        }
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(
                spec, tenantId, process.getProjectId(), process.getId());

        String apiKeySetting = String.format(
                SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, process.getProjectId(), process.getId(), apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
    }

    // ──────────────────── engineParams helpers ────────────────────

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static String nonBlankOr(@Nullable String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    /** {@link String#format} that survives misformatted templates. */
    private static String formatSafe(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            log.warn("Arthur: validator template format failed ({}), using verbatim",
                    e.toString());
            return template;
        }
    }

    private static int paramInt(
            ThinkProcessDocument process, String key, int fallback) {
        Object v = param(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean paramBool(
            ThinkProcessDocument process, String key, boolean fallback) {
        Object v = param(process, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
