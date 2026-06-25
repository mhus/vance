package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Trillian Control — reactive chat host for Nature-0 sessions.
 *
 * <p>Reply-style turn semantics: each user input triggers one
 * tool-call/text loop until the LLM produces a natural-stop reply
 * (text, no tool calls). Then the engine goes IDLE and waits for the
 * next inbox event — fresh user input from the human, or a
 * {@code task_done}/{@code task_failed}/{@code task_needs_input}
 * ProcessEvent from the paired Trillian-User worker.
 *
 * <p>Deliberately <em>not</em> reusing Arthur: Arthur ships a
 * structured-output action schema (ANSWER / DELEGATE / RELAY / …)
 * that forces every turn into one of those buckets — DELEGATE in
 * particular routes to {@code process_create}, which is the wrong
 * delegation path for Trillian (Trillian-User isn't a one-shot worker,
 * it's a long-lived peer). Building a clean reply-style engine here
 * avoids fighting that schema. See {@code planning/trillian-engine.md}
 * §2 + §11.
 *
 * <p>Compared to Lunkwill (the user-loop), Trillian-Control:
 * <ul>
 *   <li>has no {@code _terminate} tool path — it never closes itself.
 *       Session-close is the only path that ends it.</li>
 *   <li>has no wallclock / idle-stuck safety nets — conversation is
 *       human-paced, not autonomous.</li>
 *   <li>carries an {@code engineRoles} marker of
 *       {@value #ROLE_TRILLIAN_CONTROL} so Trillian-specific tools
 *       (task_enqueue, user_*, …) can gate themselves through
 *       {@code requiresEngineRoles} instead of leaking into other
 *       chat engines.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianControlEngine implements ThinkEngine {

    public static final String NAME = "trillian-control";
    public static final String VERSION = "0.1.0";
    public static final String ROLE_TRILLIAN_CONTROL = "trillian-control";

    /**
     * Engine-default tool set. Includes the Trillian-Control-specific
     * tools ({@code task_enqueue}, {@code user_*}) directly — the
     * recipe-side {@code allowedToolsAdd} is membership-neutral
     * (only re-classifies primary vs. deferred), so role-gated tools
     * that aren't in the engine's base never become visible. The
     * recipe still lists them for documentation + classification.
     */
    private static final Set<String> ENGINE_DEFAULT_TOOLS;
    static {
        java.util.LinkedHashSet<String> base = new java.util.LinkedHashSet<>();
        // Basics
        base.add("current_time");
        base.add("whoami");
        base.add("manual_read");
        base.add("manual_list");
        base.add("find_tools");
        base.add("describe_tool");
        base.add("recipe_describe");
        base.add("how_do_i");
        base.add("vance_notify");
        base.add("inbox_post");
        // Trillian-Control-specific (role-gated)
        base.add("task_enqueue");
        base.add("user_status");
        base.add("user_stop");
        base.add("user_continue");
        base.add("user_reset");
        base.add("user_clear");
        base.add("user_attr_set");
        base.add("user_attr_clear");
        base.add("user_attr_list");
        ENGINE_DEFAULT_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    private static final String DEFAULT_PROMPT_PATH =
            "_vance/prompts/trillian-control-prompt.md";

    /**
     * Failsafe prompt used only when the document cascade can't
     * resolve {@link #DEFAULT_PROMPT_PATH} — kept tiny on purpose.
     */
    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Trillian-Control. You talk with a human; you delegate "
                    + "operational tasks to the paired Trillian-User worker via "
                    + "`task_enqueue`. You do not execute work yourself.";

    private static final String MODEL_COLLAPSE_MESSAGE =
            "_Das Modell hat eine leere Antwort geliefert — "
                    + "vermutlich Kontext zu groß, Provider-Timeout, "
                    + "oder Modell-seitiger Collapse. Formuliere die "
                    + "Frage neu, oder wechsle das Modell. Brain-Log "
                    + "zeigt Details._";

    /**
     * Cap on tool-loop iterations within one turn. The chat host
     * should usually settle after 1-3 tool calls (e.g. task_enqueue +
     * reply); deep loops would indicate the LLM is confused. Acts as
     * a soft safety net so a hallucinated retry-storm doesn't burn
     * the conversation.
     */
    private static final int MAX_TOOL_LOOP_ITERATIONS = 12;

    private final ThinkProcessService thinkProcessService;
    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final StreamingProperties streamingProperties;
    private final ObjectMapper objectMapper;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer systemPromptComposer;
    private final TrillianNatureRegistry natureRegistry;
    private final ModelCatalog modelCatalog;
    private final MemoryContextLoader memoryContextLoader;
    private final MemoryCompactionService memoryCompactionService;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Trillian Control (Nature-0)";
    }

    @Override
    public String description() {
        return "Reactive chat host for Trillian Nature-0. Talks to the "
                + "human, delegates tasks to a paired Trillian-User "
                + "worker via task_enqueue. No structured-action schema.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> roles() {
        return Set.of(ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    @Override
    public boolean producesUserFacingOutput() {
        // Control's terminal events should be relayed verbatim if they
        // ever surface — they're natural-language replies for a human.
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("TrillianControl.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("TrillianControl.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("TrillianControl.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurn(process, ctx);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("TrillianControl.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── Turn loop ────────────────────

    /**
     * Reply-style turn: drain pending, render history + extras, call
     * LLM, execute any tool batch the reply requests, loop until the
     * LLM produces a natural-stop reply (text + no tool calls). Then
     * persist the reply and go IDLE.
     *
     * <p>Status-flow inside the turn: IDLE → RUNNING → IDLE. External
     * status changes (PAUSED / SUSPENDED / CLOSED) abort the loop at
     * the next iteration boundary.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        TrillianNature nature = natureRegistry.resolve(readNature(process));
        ThinkProcessStatus exitStatus = ThinkProcessStatus.IDLE;
        long turnStartMs = System.currentTimeMillis();
        try {
            nature.beforeControlTurn(process, ctx);
            ChatMessageService chatLog = ctx.chatMessageService();
            List<SteerMessage> drained = ctx.drainPending();
            List<SteerMessage> extras = persistUserInputAndCollectExtras(
                    process, chatLog, drained);

            log.debug("TrillianControl.runTurn id='{}' drained={} (uci={}, extras={})",
                    process.getId(), drained.size(),
                    drained.size() - extras.size(), extras.size());

            EngineChatFactory.EngineChatBundle bundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = bundle.chat();
            String modelAlias =
                    bundle.primaryConfig().provider() + ":" + bundle.primaryConfig().modelName();

            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(), process.getProjectId(),
                    bundle.primaryConfig().providerInstance(),
                    bundle.primaryConfig().provider(),
                    bundle.primaryConfig().modelName());
            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, extras, nature, modelInfo);

            // Turn-start compaction: identical hook to Arthur/Eddie/Ford/Lunkwill.
            // The Trillian-Control loop pairs with a Trillian-User and can
            // run for many turns; without compaction the Control session
            // would also hit the context-window limit. See
            // planning/memory-compaction.md §7.
            CompactionResult cr = memoryCompactionService.compactIfNeeded(
                    process, bundle.primaryConfig(), messages, modelInfo);
            if (cr.compacted()) {
                log.info("TrillianControl.runTurn id='{}' compaction (turn-start) ok: {} msgs → {} chars (memory='{}')",
                        process.getId(), cr.messagesCompacted(),
                        cr.summaryChars(), cr.memoryId());
                messages = buildPromptMessages(
                        process, chatLog, extras, nature, modelInfo);
            }

            log.debug("TrillianControl id='{}' model='{}' tools={} historyMsgs={}",
                    process.getId(), modelAlias, toolSpecs.size(), messages.size());

            boolean emptyRetryUsed = false;
            for (int iter = 0; iter < MAX_TOOL_LOOP_ITERATIONS; iter++) {
                ThinkProcessStatus current = readCurrentStatus(process);
                if (current == ThinkProcessStatus.SUSPENDED
                        || current == ThinkProcessStatus.CLOSED) {
                    log.info("TrillianControl id='{}' external interrupt (status={}) — exiting",
                            process.getId(), current);
                    exitStatus = null;
                    return;
                }

                ChatRequest.Builder req = ChatRequest.builder().messages(messages);
                if (!toolSpecs.isEmpty()) {
                    req.toolSpecifications(toolSpecs);
                }
                log.trace("TrillianControl id='{}' iter={} ▶ LLM call (model='{}', messages={}, toolSpecs={})",
                        process.getId(), iter, modelAlias, messages.size(), toolSpecs.size());
                long callStartMs = System.currentTimeMillis();
                AiMessage reply = streamOneIteration(
                        aiChat, req.build(), ctx, process, modelAlias);
                if (log.isTraceEnabled()) {
                    int textLen = reply.text() == null ? 0 : reply.text().length();
                    int toolCalls = reply.hasToolExecutionRequests()
                            ? reply.toolExecutionRequests().size() : 0;
                    log.trace("TrillianControl id='{}' iter={} ◀ LLM reply in {}ms text={}chars toolCalls={}",
                            process.getId(), iter, System.currentTimeMillis() - callStartMs,
                            textLen, toolCalls);
                }

                if (!reply.hasToolExecutionRequests()) {
                    String finalText = reply.text() == null ? "" : reply.text();
                    if (finalText.isBlank()) {
                        // Some providers (Gemini-2.5-flash, slow
                        // OpenAI-compat endpoints) occasionally
                        // return finish=STOP with no output. Retry
                        // once; if it stays empty, surface the
                        // collapse message.
                        if (!emptyRetryUsed) {
                            emptyRetryUsed = true;
                            log.warn("TrillianControl id='{}' iter={} empty LLM response — retrying once (model='{}')",
                                    process.getId(), iter, modelAlias);
                            continue;
                        }
                        log.warn("TrillianControl id='{}' empty LLM response after retry — surfacing (model='{}')",
                                process.getId(), modelAlias);
                        persistAssistantReply(process, chatLog, ctx,
                                MODEL_COLLAPSE_MESSAGE, drained);
                        exitStatus = ThinkProcessStatus.IDLE;
                        return;
                    }
                    persistAssistantReply(process, chatLog, ctx, finalText, drained);
                    log.info("TrillianControl id='{}' reply ({} chars, {}ms) — IDLE",
                            process.getId(), finalText.length(),
                            System.currentTimeMillis() - turnStartMs);
                    exitStatus = ThinkProcessStatus.IDLE;
                    return;
                }

                messages.add(reply);
                if (log.isTraceEnabled()) {
                    for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                        log.trace("TrillianControl id='{}' iter={} ▶ tool '{}' args={}",
                                process.getId(), iter, call.name(),
                                shortenArgs(call.arguments()));
                    }
                }
                executeToolBatch(reply.toolExecutionRequests(),
                        tools, messages, process.getId());
                // Tool-call success resets the retry budget.
                emptyRetryUsed = false;
            }
            log.warn("TrillianControl id='{}' exceeded {} tool-loop iterations — surfacing IDLE",
                    process.getId(), MAX_TOOL_LOOP_ITERATIONS);
            persistAssistantReply(process, chatLog, ctx,
                    "_Tool loop ran past " + MAX_TOOL_LOOP_ITERATIONS
                            + " iterations without a natural reply — bailing out._",
                    drained);
            exitStatus = ThinkProcessStatus.IDLE;
        } catch (RuntimeException ex) {
            log.warn("TrillianControl id='{}' turn aborted: {}", process.getId(), ex.toString());
            exitStatus = ThinkProcessStatus.BLOCKED;
            throw ex;
        } finally {
            try {
                nature.afterControlTurn(process, ctx);
            } catch (RuntimeException natureEx) {
                log.warn("TrillianControl id='{}' nature.afterControlTurn failed: {}",
                        process.getId(), natureEx.toString());
            }
            if (exitStatus != null) {
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    /**
     * Reads {@code engineParams.nature} (the Nature pin from the
     * recipe). Mirrors {@link TrillianSessionBootstrapper#readNature}
     * but inline to avoid the dependency loop.
     */
    private static String readNature(ThinkProcessDocument process) {
        if (process.getEngineParams() == null) {
            return TrillianSessionBootstrapper.DEFAULT_NATURE;
        }
        Object raw = process.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return TrillianSessionBootstrapper.DEFAULT_NATURE;
    }

    // ──────────────────── Inbox + history ────────────────────

    private List<SteerMessage> persistUserInputAndCollectExtras(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox) {
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                if (uci.content() != null && !uci.content().isBlank()) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                }
                continue;
            }
            // Persist non-UserChatInput messages in chatLog as USER-
            // role so they survive turn boundaries. Trillian-Control
            // needs to correlate task-events (task_done) with their
            // originating task_enqueue across multiple turns.
            String rendered = renderForLlm(m);
            if (rendered != null) {
                chatLog.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.USER)
                        .content(rendered)
                        .build());
            }
        }
        return List.of();
    }

    private void persistAssistantReply(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            ThinkEngineContext ctx,
            String finalText,
            List<SteerMessage> originalInbox) {
        if (finalText.isBlank()) return;
        ChatMessageDocument saved = chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(finalText)
                .build());
        if (saved != null && saved.getId() != null) {
            ctx.historyTagSink().flushTo(saved.getId(), chatLog);
        }
        Instant inResponseToAt = lastUserInputAt(originalInbox);
        ctx.emitReply(finalText, inResponseToAt, null);
    }

    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            TrillianNature nature,
            @Nullable ModelInfo modelInfo) {
        List<ChatMessage> messages = new ArrayList<>();
        // System prompt = engine-default + nature overlay + memory-context
        // (languages, agent-doc, ARCHIVED_CHAT summary, RAG auto-inject).
        // Same shape Arthur/Eddie/Ford/Lunkwill build via MemoryContextLoader.
        String systemPrompt = composeSystemPrompt(process, nature, modelInfo);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(systemPrompt));
        // Current-date block (recipe-param promptDateGranularity:
        // auto/day/hour). DYNAMIC — date rollover stays behind the
        // cache marker. See PromptDateBlock.
        de.mhus.vance.brain.prompt.PromptDateBlock.appendDynamicMessage(
                messages, process, modelInfo == null ? null : modelInfo.size());
        for (ChatMessageDocument msg : chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId())) {
            messages.add(toLangchain(msg));
        }
        for (SteerMessage m : inboxExtras) {
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }
        return messages;
    }

    private String composeSystemPrompt(
            ThinkProcessDocument process,
            TrillianNature nature,
            @Nullable ModelInfo modelInfo) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        String engineDefault = enginePromptResolver.resolve(
                process, basePath, ENGINE_FALLBACK_PROMPT);
        // Nature overlay — appended at the end of the engine-default
        // prompt. Empty for Nature-0; personality / reflexion priming
        // for Nature-A+.
        String addendum = nature.controlPromptAddendum(process);
        if (addendum != null && !addendum.isBlank()) {
            engineDefault = engineDefault + "\n\n" + addendum;
        }
        PromptContextBuilder ctxBuilder = PromptContextBuilder
                .forProcess(process, modelInfo)
                .engine(NAME);
        return systemPromptComposer.compose(process, engineDefault, ctxBuilder);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return fallback;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    private @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) return null;
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\" type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.Reply rep) {
            StringBuilder sb = new StringBuilder();
            sb.append("<peer-reply sourceProcessId=\"")
                    .append(escapeAttr(rep.sourceProcessId()))
                    .append("\"");
            if (rep.sourceProcessName() != null) {
                sb.append(" sourceProcessName=\"")
                        .append(escapeAttr(rep.sourceProcessName()))
                        .append("\"");
            }
            sb.append(">");
            if (rep.content() != null) {
                sb.append(escapeText(rep.content()));
            }
            sb.append("</peer-reply>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ToolResult tr) {
            StringBuilder sb = new StringBuilder();
            sb.append("<tool-result toolCallId=\"")
                    .append(escapeAttr(tr.toolCallId()))
                    .append("\" toolName=\"")
                    .append(escapeAttr(tr.toolName()))
                    .append("\" status=\"")
                    .append(tr.status().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            if (tr.error() != null) {
                sb.append("error: ").append(escapeText(tr.error()));
            } else if (tr.result() != null) {
                sb.append(escapeText(tr.result().toString()));
            }
            sb.append("</tool-result>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ExternalCommand ec) {
            return "<external-command command=\""
                    + escapeAttr(ec.command()) + "\">"
                    + escapeText(ec.params() == null ? "" : ec.params().toString())
                    + "</external-command>";
        }
        return null;
    }

    // ──────────────────── LLM call ────────────────────

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
                    log.warn("TrillianControl chunk-publish threw: {}", e.toString());
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
            ChatResponse response = done.get();
            llmCallTracker.record(
                    process, request, response,
                    System.currentTimeMillis() - startMs, modelAlias);
            return response.aiMessage();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("TrillianControl streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("TrillianControl streaming interrupted", e);
        }
    }

    // ──────────────────── Tool dispatch ────────────────────

    private void executeToolBatch(
            List<ToolExecutionRequest> calls,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            String processId) {
        for (ToolExecutionRequest call : calls) {
            String serialized = invokeOne(tools, call, processId);
            messages.add(ToolExecutionResultMessage.from(call, serialized));
        }
    }

    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("TrillianControl id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("TrillianControl id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("TrillianControl id='{}' tool='{}' unexpected failure: {}",
                    processId, call.name(), e.toString());
            return errorJson("Tool failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        return objectMapper.readValue(raw, Map.class);
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (RuntimeException e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    // ──────────────────── Helpers ────────────────────

    private ThinkProcessStatus readCurrentStatus(ThinkProcessDocument process) {
        return thinkProcessService.findById(process.getId())
                .map(ThinkProcessDocument::getStatus)
                .orElse(process.getStatus());
    }

    private static String shortenArgs(@Nullable String raw) {
        if (raw == null) return "";
        String trimmed = raw.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 199) + "…";
    }

    private static @Nullable Instant lastUserInputAt(List<SteerMessage> inbox) {
        Instant best = null;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                Instant at = uci.at();
                if (at != null && (best == null || at.isAfter(best))) {
                    best = at;
                }
            }
        }
        return best;
    }

    private static String escapeAttr(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String escapeText(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
