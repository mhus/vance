package de.mhus.vance.brain.lunkwill;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Lunkwill — focused-worker engine. Pi-style loop: drain inbox →
 * LLM → execute tool calls → repeat until one of four stop paths
 * fires.
 *
 * <h2>Stop paths (hardcoded, no recipe config)</h2>
 * <ol>
 *   <li><b>Natural stop</b> — LLM responds with no tool calls; its
 *       text is the final answer, process closes {@code DONE}.</li>
 *   <li><b>Tool-driven terminate</b> — a tool result carries
 *       {@code "_terminate": true}; loop exits after the current
 *       batch, process closes {@code DONE}.</li>
 *   <li><b>External interrupt</b> — process status is set to
 *       {@code SUSPENDED} or {@code CLOSED} from outside (UI
 *       Stop button, Arthur's {@code ProcessStopTool}, session
 *       suspend cascade, lane-kill); the loop exits at the next
 *       turn boundary.</li>
 *   <li><b>Safety nets</b> — wallclock budget exceeded
 *       ({@code vance.lunkwill.maxWallclockMinutes}) or N
 *       consecutive identical tool-call batches detected
 *       ({@code vance.lunkwill.idleStuckThreshold}); process moves
 *       to {@code BLOCKED}.</li>
 * </ol>
 *
 * <p>No {@code maxIterations} cap — Lunkwill is endless-by-design.
 *
 * <p>See {@code planning/lunkwill-engine.md} and
 * {@code planning/agent-stop-conditions.md}.
 */
@Component
@EnableConfigurationProperties(LunkwillProperties.class)
@RequiredArgsConstructor
@Slf4j
public class LunkwillEngine implements ThinkEngine {

    public static final String NAME = "lunkwill";
    public static final String VERSION = "0.5.0";

    /**
     * Engine-intrinsic tool baseline — the minimum every Lunkwill
     * recipe needs, regardless of domain. Domain-specific tools
     * ({@code client_file_*}, {@code client_exec_*}, GitHub-API for
     * fook-upstream, MCP-reconnect for repair, …) come from each
     * recipe via {@code allowedToolsAdd}.
     *
     * <p>Returned by {@link #allowedTools()} so {@link
     * de.mhus.vance.brain.recipe.RecipeResolver#computeAllowed} treats
     * it as the engine default: effective set =
     * {@code (engineDefault ∪ recipe.add) ∖ recipe.remove}. Without
     * this override Lunkwill would default to "no engine-level
     * restriction" and the LLM would see the full tenant tool buffet
     * (~130 schemas, ~35k input tokens) on every turn.
     */
    private static final Set<String> ENGINE_DEFAULT_TOOLS;
    static {
        java.util.LinkedHashSet<String> base = new java.util.LinkedHashSet<>();
        // discovery / introspection
        base.add("find_tools");
        base.add("describe_tool");
        base.add("how_do_i");
        base.add("manual_read");
        base.add("manual_list");
        base.add("recipe_describe");
        base.add("tool_result_read");
        // sub-worker spawn — Lunkwill's escape hatch when a task
        // needs strategic planning or different skill set
        base.add("process_create");
        base.add("process_status");
        // user-facing signals
        base.add("vance_notify");
        // basics
        base.add("current_time");
        base.add("whoami");
        // Generic work-target file/exec wrappers + work_target_get/set.
        // The 12 file_*/exec_* tools dispatch to client_* or work_*
        // backends per the per-process WorkTarget; see
        // de.mhus.vance.brain.tools.worktarget.BaseEngineTools.
        base.addAll(de.mhus.vance.brain.tools.worktarget.BaseEngineTools.WORK_TARGET);
        ENGINE_DEFAULT_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    /**
     * Document cascade path for the engine-default system prompt.
     * Recipe param {@code promptDocument} can override the path; the
     * recipe's {@code promptPrefix} is then overlaid by
     * {@link SystemPromptComposer}.
     */
    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/lunkwill-prompt.md";

    /**
     * Surfaced as the assistant message when the LLM returns neither
     * text nor tool calls — a model-side collapse, not a clean
     * natural stop. Without this message the user just sees the
     * turn stall silently and has no clue the worker bailed.
     */
    private static final String MODEL_COLLAPSE_MESSAGE =
            "_Das Modell hat eine leere Antwort geliefert — "
                    + "vermutlich Kontext zu groß oder Modell-seitiger Collapse. "
                    + "Formuliere die Frage neu, kürze den Verlauf, oder "
                    + "wechsle das Modell. Der Worker bleibt BLOCKED bis zur "
                    + "nächsten Eingabe._";

    /**
     * Last-resort hardcoded system prompt — used only when neither the
     * document cascade nor a recipe-supplied prompt resolve. Keep tiny
     * on purpose so a misconfigured spawn still produces a coherent
     * (if generic) worker rather than an unprompted LLM.
     */
    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Lunkwill, a focused worker. Drive the task in multiple turns "
                    + "using the available tools, then stop. When you have a final "
                    + "answer, reply with plain text and no tool call — that ends "
                    + "the loop. Use the recipe's task-complete tool (if any) for "
                    + "explicit structured completion.";

    private final ThinkProcessService thinkProcessService;
    private final LunkwillProperties properties;
    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final StreamingProperties streamingProperties;
    private final ObjectMapper objectMapper;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer systemPromptComposer;
    private final SkillResolver skillResolver;
    private final SkillPromptComposer skillPromptComposer;
    private final SessionService sessionService;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Lunkwill (Focused Worker)";
    }

    @Override
    public String description() {
        return "Pi-style focused worker — drain, LLM, tools, repeat until done. "
                + "First validating recipe: coding.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Lunkwill.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Lunkwill.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Lunkwill.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Defer to runTurn — it will drain whatever else is in the inbox.
        runTurn(process, ctx);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Lunkwill.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── Loop ────────────────────

    /**
     * Pi-style loop. Wakes whenever the inbox gets fresh material;
     * iterates LLM → tools → LLM until one of the stop paths fires.
     * Each iteration checks for an external interrupt first so a
     * STOP request from outside is honoured promptly.
     *
     * <p>Lifecycle matrix:
     * <pre>
     *   Stop path                | Worker (has parent)     | Session-primary
     *   -------------------------+-------------------------+----------------
     *   Natural stop             | IDLE  (await steer)     | IDLE (await user)
     *   Tool-terminate           | CLOSED + DONE           | IDLE (signal, no close)
     *   External interrupt       | already set externally  | same
     *   Wallclock / idle-stuck   | BLOCKED                 | same
     * </pre>
     * The single branching point is {@code _terminate}: workers close
     * out so the parent's delegation pointer can release; session-
     * primary processes stay alive because the user is still talking
     * to them.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // Wallclock budget is per-turn, not per-process-lifetime. Resuming
        // a session that's been idle for a day must not trip the safety
        // net on its first re-steer just because the process was created
        // long ago — the LunkwillEngine.runTurn invocation is the unit we
        // want to bound.
        long startMs = System.currentTimeMillis();
        long deadlineMs = startMs + (long) properties.getMaxWallclockMinutes() * 60_000L;
        boolean isWorker = process.getParentProcessId() != null
                && !process.getParentProcessId().isBlank();

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Exit status to write in finally — null means "leave alone"
        // (status already set externally, or the engine has closed
        // the process itself).
        ThinkProcessStatus exitStatus = ThinkProcessStatus.IDLE;
        try {
            // 1) Persist user input from inbox, collect non-UCI items as turn-local extras.
            ChatMessageService chatLog = ctx.chatMessageService();
            List<SteerMessage> drained = ctx.drainPending();
            List<SteerMessage> extras = persistUserInputAndCollectExtras(process, chatLog, drained);

            // 2) Build the LLM bundle + initial message list.
            EngineChatFactory.EngineChatBundle bundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = bundle.chat();
            String modelAlias =
                    bundle.primaryConfig().provider() + ":" + bundle.primaryConfig().modelName();

            // Resolve recipe-pinned / Foot-activated skills (Layer 1+2)
            // through the user/project/tenant/bundled cascade. Skills add
            // both a prompt block (composed by SkillPromptComposer) and
            // tool entries (merged into the per-turn allow-set). No
            // auto-trigger here — activation is either recipe.defaultActiveSkills
            // or explicit /skill add via ProcessSkillCommand. See
            // CLAUDE.md "Skills" and specification/skills.md.
            List<ResolvedSkill> activeSkills = resolveActiveSkills(process);
            ContextToolsApi tools = ctx.tools()
                    .withAdditional(skillPromptComposer.mergedTools(activeSkills));
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            List<ChatMessage> messages = buildPromptMessages(process, chatLog, extras, activeSkills);

            // 3) Pi-style loop — no max-iters, only natural / terminate / external / safety stops.
            Deque<String> recentToolHashes = new ArrayDeque<>(properties.getIdleStuckThreshold());

            while (true) {
                // External interrupt — graceful exit between turns.
                ThinkProcessStatus current = readCurrentStatus(process);
                if (current == ThinkProcessStatus.SUSPENDED
                        || current == ThinkProcessStatus.CLOSED) {
                    log.info("Lunkwill id='{}' external interrupt (status={}) — exiting loop",
                            process.getId(), current);
                    exitStatus = null;
                    return;
                }

                // Wallclock safety net.
                if (System.currentTimeMillis() > deadlineMs) {
                    log.warn("Lunkwill id='{}' wallclock exceeded ({} min) — BLOCKED",
                            process.getId(), properties.getMaxWallclockMinutes());
                    exitStatus = ThinkProcessStatus.BLOCKED;
                    return;
                }

                ChatRequest.Builder req = ChatRequest.builder().messages(messages);
                if (!toolSpecs.isEmpty()) {
                    req.toolSpecifications(toolSpecs);
                }
                AiMessage reply = streamOneIteration(
                        aiChat, req.build(), ctx, process, modelAlias);

                // Stop path: natural stop (no tool calls). Always
                // transition to IDLE — context stays alive for a
                // follow-up turn (parent's process_steer in worker
                // mode, user's next chat message in session-primary
                // mode). Explicit "done forever" only happens via
                // tool-terminate below.
                //
                // Edge case: empty LLM response. When the model
                // returns neither text nor tool calls — typically a
                // model-side collapse from over-large context or a
                // provider timeout — the standard natural-stop path
                // would silently drop the turn (nothing persisted,
                // user sees no reply). Treat it as a stall instead:
                // surface a clear assistant message so the user
                // knows the worker bailed, and park BLOCKED so the
                // attention is on the broken state rather than
                // looking ready for the next input.
                if (!reply.hasToolExecutionRequests()) {
                    String finalText = reply.text() == null ? "" : reply.text();
                    if (finalText.isBlank()) {
                        log.warn(
                                "Lunkwill id='{}' empty LLM response (no text, no tool calls) — BLOCKED",
                                process.getId());
                        persistAssistantReply(process, chatLog, ctx,
                                MODEL_COLLAPSE_MESSAGE, drained);
                        exitStatus = ThinkProcessStatus.BLOCKED;
                        return;
                    }
                    persistAssistantReply(process, chatLog, ctx, finalText, drained);
                    log.info("Lunkwill id='{}' natural stop — awaiting follow-up ({} chars)",
                            process.getId(), finalText.length());
                    exitStatus = ThinkProcessStatus.IDLE;
                    return;
                }

                // Idle-stuck safety net (over consecutive batches).
                String batchHash = hashToolCalls(reply.toolExecutionRequests());
                if (isIdleStuck(recentToolHashes, batchHash)) {
                    log.warn("Lunkwill id='{}' idle-stuck on '{}' — BLOCKED",
                            process.getId(), batchHash);
                    exitStatus = ThinkProcessStatus.BLOCKED;
                    return;
                }

                // Execute tools, append results, watch for _terminate.
                messages.add(reply);
                boolean terminate = executeToolBatch(
                        reply.toolExecutionRequests(), tools, messages, process.getId());

                if (terminate) {
                    if (isWorker) {
                        // Worker: explicit "done forever" — close so
                        // the parent's delegation pointer releases.
                        // No additional assistant text — the task-
                        // complete tool's summary is the canonical
                        // outcome and lives in tool-result history;
                        // ParentNotificationListener.enrichWithLastReply
                        // attaches the last assistant message to the
                        // DONE event for the parent.
                        log.info("Lunkwill id='{}' worker tool-terminate — CLOSED (DONE)",
                                process.getId());
                        thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
                        exitStatus = null;
                    } else {
                        // Session-primary: the recipe's task-complete
                        // tool signals "this task is finished" but the
                        // session keeps going — the user can ask the
                        // next thing. Stay IDLE.
                        log.info("Lunkwill id='{}' session-primary tool-terminate — IDLE",
                                process.getId());
                        exitStatus = ThinkProcessStatus.IDLE;
                    }
                    return;
                }

                // Loop continues: next iteration's LLM call will see the tool results.
            }
        } catch (RuntimeException ex) {
            log.warn("Lunkwill id='{}' turn aborted: {}", process.getId(), ex.toString());
            exitStatus = ThinkProcessStatus.BLOCKED;
            throw ex;
        } finally {
            // Drain one-shot skills (Ford-compatible behaviour): they
            // only apply to the turn that activated them.
            dropOneShotSkills(process);
            if (exitStatus != null) {
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    // ──────────────────── Inbox handling ────────────────────

    /**
     * Append {@code UserChatInput} entries to chat history (so future
     * turns see them) and collect non-UCI items (ProcessEvents,
     * ToolResults, ExternalCommands) as turn-local extras that get
     * rendered as user-role messages in this turn only.
     */
    private List<SteerMessage> persistUserInputAndCollectExtras(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox) {
        List<SteerMessage> extras = new ArrayList<>();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null && !uci.content().isBlank()) {
                chatLog.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.USER)
                        .content(uci.content())
                        .build());
            } else if (!(m instanceof SteerMessage.UserChatInput)) {
                extras.add(m);
            }
        }
        return extras;
    }

    private void persistAssistantReply(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            ThinkEngineContext ctx,
            String finalText,
            List<SteerMessage> originalInbox) {
        if (finalText.isBlank()) return;
        // Always persist to Mongo so peer_read_chat_memory can read
        // the worker's transcript later — only the live UI-emit is
        // suppressed for hidden processes.
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
        if (process.isHiddenFromUi()) {
            return;
        }
        Instant inResponseToAt = lastUserInputAt(originalInbox);
        ctx.emitReply(finalText, inResponseToAt, null);
    }

    // ──────────────────── Prompt assembly ────────────────────

    /**
     * Builds the message list for one LLM iteration: composed system
     * prompt (engine-default from the document cascade + recipe
     * overlay rendered by {@link SystemPromptComposer}), an optional
     * skills system block (when the process has active skills),
     * persisted chat history, then turn-local extras as user-role
     * messages.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            List<ResolvedSkill> activeSkills) {
        List<ChatMessage> messages = new ArrayList<>();
        PromptContextBuilder ctxBuilder = PromptContextBuilder
                .forProcess(process, /*modelInfo*/ null)
                .engine(NAME);
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        String engineDefault = enginePromptResolver.resolve(
                process, basePath, ENGINE_FALLBACK_PROMPT);
        messages.add(SystemMessage.from(
                systemPromptComposer.compose(process, engineDefault, ctxBuilder)));
        String skillSection = skillPromptComposer.compose(activeSkills, ctxBuilder.build());
        if (skillSection != null && !skillSection.isBlank()) {
            messages.add(SystemMessage.from(skillSection));
        }
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

    // ──────────────────── Skills (Layer 1+2) ────────────────────

    /**
     * Resolves the process's persisted {@link ActiveSkillRefEmbedded}s
     * into ready-to-use {@link ResolvedSkill}s through the user/project/
     * tenant/bundled cascade. Mirrors {@code Ford.resolveActiveSkills}:
     * skills that no longer resolve (e.g. a user deleted a private
     * skill mid-session) are skipped with a warning rather than
     * failing the turn.
     */
    private List<ResolvedSkill> resolveActiveSkills(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            try {
                skillResolver.resolve(scope, ref.getName())
                        .ifPresentOrElse(out::add, () -> log.warn(
                                "Lunkwill id='{}' active skill '{}' no longer resolves — skipping",
                                process.getId(), ref.getName()));
            } catch (UnknownSkillException e) {
                log.warn("Lunkwill id='{}' active skill '{}' unknown — skipping",
                        process.getId(), ref.getName());
            }
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElse(null);
        String userId = session != null && session.getUserId() != null
                && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && session.getProjectId() != null
                && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    private void dropOneShotSkills(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return;
        boolean anyOneShot = active.stream().anyMatch(ActiveSkillRefEmbedded::isOneShot);
        if (!anyOneShot) return;
        List<ActiveSkillRefEmbedded> kept = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            if (!ref.isOneShot()) {
                kept.add(ref);
            }
        }
        process.setActiveSkills(kept);
        thinkProcessService.replaceActiveSkills(process.getId(), kept);
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
                    .append(pe.type().name().toLowerCase(java.util.Locale.ROOT))
                    .append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ToolResult tr) {
            StringBuilder sb = new StringBuilder();
            sb.append("<tool-result toolCallId=\"")
                    .append(escapeAttr(tr.toolCallId()))
                    .append("\" toolName=\"")
                    .append(escapeAttr(tr.toolName()))
                    .append("\" status=\"")
                    .append(tr.status().name().toLowerCase(java.util.Locale.ROOT))
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

        // Hidden processes (e.g. Trillian-User) don't push streaming
        // chunks to the session chat-panel — their text output is
        // internal-only. See ThinkProcessDocument.hiddenFromUi.
        boolean hidden = process.isHiddenFromUi();
        ChunkBatcher batcher = new ChunkBatcher(
                streamingProperties.getChunkCharThreshold(),
                streamingProperties.getChunkFlushMs(),
                chunk -> {
                    if (hidden) return;
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
                    log.warn("Lunkwill chunk-publish threw: {}", e.toString());
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
            throw new AiChatException("Lunkwill streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Lunkwill streaming interrupted", e);
        }
    }

    // ──────────────────── Tool dispatch ────────────────────

    /**
     * Executes every tool call in the batch. Returns {@code true} iff
     * at least one tool result carried
     * {@link LunkwillTermination#RESULT_TERMINATE_KEY} = true — the
     * caller treats this as a terminal signal and closes the process
     * with {@code DONE}.
     */
    private boolean executeToolBatch(
            List<ToolExecutionRequest> calls,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            String processId) {
        boolean terminate = false;
        for (ToolExecutionRequest call : calls) {
            ToolInvocationResult invoked = invokeOne(tools, call, processId);
            messages.add(ToolExecutionResultMessage.from(call, invoked.serialized));
            if (invoked.terminate) {
                terminate = true;
            }
        }
        return terminate;
    }

    private record ToolInvocationResult(String serialized, boolean terminate) {}

    private ToolInvocationResult invokeOne(
            ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Lunkwill id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return new ToolInvocationResult(errorJson("Invalid tool arguments: " + e.getMessage()), false);
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            boolean terminate = isTruthy(result.get(LunkwillTermination.RESULT_TERMINATE_KEY));
            return new ToolInvocationResult(objectMapper.writeValueAsString(result), terminate);
        } catch (ToolException e) {
            log.info("Lunkwill id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return new ToolInvocationResult(errorJson(e.getMessage()), false);
        } catch (RuntimeException e) {
            log.warn("Lunkwill id='{}' tool='{}' unexpected failure: {}",
                    processId, call.name(), e.toString());
            return new ToolInvocationResult(errorJson("Tool failed: " + e.getMessage()), false);
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

    // ──────────────────── Safety / interrupt helpers ────────────────────

    private ThinkProcessStatus readCurrentStatus(ThinkProcessDocument process) {
        return thinkProcessService.findById(process.getId())
                .map(ThinkProcessDocument::getStatus)
                .orElse(process.getStatus());
    }

    /**
     * Maintains a sliding window of the last N tool-call batch hashes.
     * Returns true iff the window is full and every entry equals the
     * incoming batch hash — i.e. the LLM is calling the same tools
     * with the same arguments N times in a row.
     */
    private boolean isIdleStuck(Deque<String> recentHashes, String batchHash) {
        int threshold = properties.getIdleStuckThreshold();
        if (threshold <= 0) return false;
        recentHashes.addLast(batchHash);
        while (recentHashes.size() > threshold) {
            recentHashes.removeFirst();
        }
        if (recentHashes.size() < threshold) return false;
        for (String h : recentHashes) {
            if (!h.equals(batchHash)) return false;
        }
        return true;
    }

    private String hashToolCalls(List<ToolExecutionRequest> calls) {
        StringBuilder sb = new StringBuilder();
        for (ToolExecutionRequest c : calls) {
            sb.append(c.name()).append('(').append(c.arguments() == null ? "" : c.arguments()).append(")|");
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private boolean isTruthy(@Nullable Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return s.equalsIgnoreCase("true");
        return false;
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
