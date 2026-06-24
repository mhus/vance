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
 * Trillian-User — the agentic user-loop in Nature-0.
 *
 * <p>Lives in its own session owned by a {@code _trillian-0XXXX}
 * service-account. Wakes on {@code task_request} ProcessEvents
 * dispatched from a paired Trillian-Control. Drains the inbox,
 * decides what to do, spawns worker processes via
 * {@code process_create}, observes them, validates, reports back
 * via {@code task_complete} / {@code task_failed} /
 * {@code task_needs_input} — all of which dispatch ProcessEvents
 * back to Control (cross-session via
 * {@link de.mhus.vance.brain.enginemessage.EngineMessageRouter}).
 *
 * <p>Loop shape: <b>endless-but-sleepy</b>. Natural-stop replies
 * (text, no tool calls) transition to IDLE; the worker waits for
 * the next inbox event (a new task or a child-status notification).
 * Empty LLM responses go IDLE too — there is no BLOCKED collapse
 * here, the Trillian-User isn't a chat-host and its quiet turns are
 * fine.
 *
 * <p><b>What Trillian-User cannot do directly</b> (by design — its
 * tool set excludes file/exec/client tools): write files, run
 * shells, touch the foot client's filesystem. All of that goes
 * through spawned workers in dedicated worker processes which carry
 * those tools. The Trillian-User is the orchestrator, not the doer.
 *
 * <p><b>Cross-project capability</b>: declares
 * {@link #allowsCrossProjectSpawn()} {@code = true} so Vance routes
 * worker spawns / ProcessEvents across project boundaries. Nature-0
 * doesn't expose a {@code cross_process_create} tool yet — the
 * default {@code process_create} spawns workers in Trillian-User's
 * own project. True cross-project is a Nature-A+ extension.
 *
 * <p>See {@code planning/trillian-engine.md} §2, §6, §9.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianUserEngine implements ThinkEngine {

    public static final String NAME = "trillian-user";
    public static final String VERSION = "0.1.0";
    public static final String ROLE_TRILLIAN_USER = "trillian-user";

    /**
     * Engine-default tool set. Deliberately excludes file_*, exec_*,
     * client_*, work_file_*, work_exec_* — Trillian-User must
     * delegate that work to spawned workers.
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
        // Project visibility (read-only). Note: doc_*-tools are NOT
        // listed — they hardcode ctx.projectId() and silently ignore a
        // projectId parameter the LLM might pass. Trillian-User must
        // not call them directly; cross-project document work goes
        // through spawned workers via cross_process_create instead.
        base.add("project_list");
        // Worker spawn + observation
        base.add("process_create");          // same-project (caller's home)
        base.add("cross_process_create");    // cross-project (NEW, role-gated)
        base.add("process_status");
        base.add("process_steer");
        base.add("process_history_text");
        base.add("process_list");
        base.add("process_observe");
        // Trillian-User-specific (gated on ROLE_TRILLIAN_USER)
        base.add("task_complete");
        base.add("task_failed");
        base.add("task_needs_input");
        base.add("peer_read_chat_memory");
        ENGINE_DEFAULT_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    private static final String DEFAULT_PROMPT_PATH =
            "_vance/prompts/trillian-user-prompt.md";

    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Trillian-User. You receive task_request events from "
                    + "your paired Trillian-Control. For each task you spawn a "
                    + "worker via process_create, observe it, validate the result, "
                    + "and report back with task_complete / task_failed / "
                    + "task_needs_input. You do not execute work yourself.";

    /** Cap on tool-loop iterations within one turn. */
    private static final int MAX_TOOL_LOOP_ITERATIONS = 24;

    private final ThinkProcessService thinkProcessService;
    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final StreamingProperties streamingProperties;
    private final ObjectMapper objectMapper;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer systemPromptComposer;
    private final TrillianNatureRegistry natureRegistry;
    private final ModelCatalog modelCatalog;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Trillian User (Nature-0)";
    }

    @Override
    public String description() {
        return "Agentic user-loop. Receives task_request events, "
                + "spawns workers, observes, validates, reports back "
                + "via task_complete events. Owns its session as the "
                + "_trillian-0XXXX service-account.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> roles() {
        return Set.of(ROLE_TRILLIAN_USER);
    }

    @Override
    public Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    @Override
    public boolean allowsCrossProjectSpawn() {
        // Architecturally prepared: workers in foreign projects can
        // be spawned (when a cross_process_create-style tool ships
        // in Nature-A+). For Nature-0 we don't expose such a tool yet,
        // but the flag lets routed events flow correctly when we do.
        return true;
    }

    @Override
    public boolean asyncSteer() {
        // Treat Trillian-User like a worker w.r.t. process_steer: a
        // caller (Control) shouldn't block on it. It drains its inbox
        // asynchronously and reports back via events.
        return true;
    }

    @Override
    public boolean producesUserFacingOutput() {
        // Trillian-User terminal events carry orchestrator-level
        // summaries (e.g. "all 3 sub-tasks done") — Control relays
        // them verbatim to the human as the natural-language reply.
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("TrillianUser.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("TrillianUser.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("TrillianUser.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurn(process, ctx);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("TrillianUser.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── Turn loop ────────────────────

    /**
     * Endless-but-sleepy worker loop. Drain pending → render
     * messages → LLM → execute tool batch → loop until the LLM
     * produces a natural-stop reply (text + no tool calls, or
     * empty). Then go IDLE and wait for the next inbox event.
     *
     * <p>Unlike Lunkwill, there is no {@code _terminate} stop path
     * and no BLOCKED-on-empty quirk. Trillian-User stays alive for
     * its session's whole lifetime; quiet turns are normal.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        TrillianNature nature = natureRegistry.resolve(readNature(process));
        ThinkProcessStatus exitStatus = ThinkProcessStatus.IDLE;
        long turnStartMs = System.currentTimeMillis();
        try {
            nature.beforeUserTurn(process, ctx);
            ChatMessageService chatLog = ctx.chatMessageService();
            List<SteerMessage> drained = ctx.drainPending();
            List<SteerMessage> extras = persistUserInputAndCollectExtras(
                    process, chatLog, drained);

            log.debug("TrillianUser.runTurn id='{}' drained={} (uci={}, extras={})",
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

            log.debug("TrillianUser id='{}' model='{}' tools={} historyMsgs={}",
                    process.getId(), modelAlias, toolSpecs.size(), messages.size());

            for (int iter = 0; iter < MAX_TOOL_LOOP_ITERATIONS; iter++) {
                ThinkProcessStatus current = readCurrentStatus(process);
                if (current == ThinkProcessStatus.SUSPENDED
                        || current == ThinkProcessStatus.CLOSED) {
                    log.info("TrillianUser id='{}' external interrupt (status={}) — exiting",
                            process.getId(), current);
                    exitStatus = null;
                    return;
                }

                ChatRequest.Builder req = ChatRequest.builder().messages(messages);
                if (!toolSpecs.isEmpty()) {
                    req.toolSpecifications(toolSpecs);
                }
                log.trace("TrillianUser id='{}' iter={} ▶ LLM call (model='{}', messages={}, toolSpecs={})",
                        process.getId(), iter, modelAlias, messages.size(), toolSpecs.size());
                long callStartMs = System.currentTimeMillis();
                AiMessage reply = streamOneIteration(
                        aiChat, req.build(), ctx, process, modelAlias);
                if (log.isTraceEnabled()) {
                    int textLen = reply.text() == null ? 0 : reply.text().length();
                    int toolCalls = reply.hasToolExecutionRequests()
                            ? reply.toolExecutionRequests().size() : 0;
                    log.trace("TrillianUser id='{}' iter={} ◀ LLM reply in {}ms text={}chars toolCalls={}",
                            process.getId(), iter, System.currentTimeMillis() - callStartMs,
                            textLen, toolCalls);
                }

                if (!reply.hasToolExecutionRequests()) {
                    String finalText = reply.text() == null ? "" : reply.text();
                    if (!finalText.isBlank()) {
                        persistAssistantReply(process, chatLog, finalText);
                    }
                    log.info("TrillianUser id='{}' natural stop ({} chars, {}ms) — IDLE",
                            process.getId(), finalText.length(),
                            System.currentTimeMillis() - turnStartMs);
                    exitStatus = ThinkProcessStatus.IDLE;
                    return;
                }

                messages.add(reply);
                if (log.isTraceEnabled()) {
                    for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                        log.trace("TrillianUser id='{}' iter={} ▶ tool '{}' args={}",
                                process.getId(), iter, call.name(),
                                shortenArgs(call.arguments()));
                    }
                }
                executeToolBatch(reply.toolExecutionRequests(),
                        tools, messages, process.getId());
            }
            log.warn("TrillianUser id='{}' exceeded {} tool-loop iterations — surfacing IDLE",
                    process.getId(), MAX_TOOL_LOOP_ITERATIONS);
            exitStatus = ThinkProcessStatus.IDLE;
        } catch (RuntimeException ex) {
            log.warn("TrillianUser id='{}' turn aborted: {}", process.getId(), ex.toString());
            exitStatus = ThinkProcessStatus.BLOCKED;
            throw ex;
        } finally {
            try {
                nature.afterUserTurn(process, ctx);
            } catch (RuntimeException natureEx) {
                log.warn("TrillianUser id='{}' nature.afterUserTurn failed: {}",
                        process.getId(), natureEx.toString());
            }
            if (exitStatus != null) {
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    /**
     * Reads {@code engineParams.nature} (the Nature pin from the
     * recipe).
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
            // Persist non-UserChatInput messages (ProcessEvents,
            // Replies, ToolResults, ExternalCommands) in chatLog as
            // USER-role with their rendered XML — otherwise they only
            // live for the current turn's extras list and get lost on
            // the next wake-up. Critical for Trillian-User which has
            // to correlate task_request (turn N) with worker-reply
            // (turn N+1) by taskId.
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
        // Returning empty extras now — everything has been persisted
        // and will reach the LLM via activeHistory in buildPromptMessages.
        return List.of();
    }

    /**
     * Persists the worker's text to Mongo (for peer_read_chat_memory
     * introspection later). No client-side emit — the Trillian-User
     * session is headless and has no foot/web connection to push to.
     */
    private void persistAssistantReply(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            String finalText) {
        chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(finalText)
                .build());
    }

    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            TrillianNature nature,
            @Nullable ModelInfo modelInfo) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(composeSystemPrompt(process, nature, modelInfo)));
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
        String addendum = nature.userPromptAddendum(process);
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
            // Worker natural-stop replies arrive as Reply messages.
            // Surface them clearly so the LLM understands a spawned
            // worker has produced its final answer (even if the
            // worker didn't call trillian_done, this is its result).
            StringBuilder sb = new StringBuilder();
            sb.append("<worker-reply sourceProcessId=\"")
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
            sb.append("</worker-reply>");
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

        // Trillian-User session is headless — no client to stream to.
        // We still run the streaming chat for token-by-token tracking
        // but skip the publish entirely.
        ChunkBatcher batcher = new ChunkBatcher(
                streamingProperties.getChunkCharThreshold(),
                streamingProperties.getChunkFlushMs(),
                chunk -> {
                    // intentionally no-op: nothing to stream to a
                    // bound connection that doesn't exist
                });

        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) return;
                try {
                    batcher.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("TrillianUser chunk-publish threw: {}", e.toString());
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
            throw new AiChatException("TrillianUser streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("TrillianUser streaming interrupted", e);
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
            log.warn("TrillianUser id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("TrillianUser id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("TrillianUser id='{}' tool='{}' unexpected failure: {}",
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

    private static String escapeAttr(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String escapeText(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
