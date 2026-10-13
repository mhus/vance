package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer;
import de.mhus.vance.brain.frankie.FrankieEngine;
import de.mhus.vance.brain.frankie.FrankieProperties;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ForeignPromptText;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.skill.SkillTurnSupport;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversation head of a multi-headed Trillian: Frankie's loop, made
 * conversational — one process per discussion thread, woken per
 * contribution, answering in its thread, handing real work to its loop.
 *
 * <p><b>Why an engine and not a recipe on a chat engine.</b> The same
 * argument as {@link TrillianWorkerEngine}, from the other side: a head
 * is a worker's loop shape (drain, model, tools, natural stop — park
 * IDLE, no termination tool) with a conversation surface instead of a
 * work surface. The tools are not the recipe's to pick: {@code
 * thread_message_add} is the head's <em>primary</em> answer channel (a
 * worker-pool classification would demote it to deferred, where a model
 * that must answer may not even find the tool), and nothing of the worker
 * core — file, exec, documents — belongs in a conversation. {@code
 * allowedTools()} is the engine's own small surface; a recipe trims
 * prompts and models, not membership.
 *
 * <p><b>What changes.</b> Three seams, none of Frankie's protocol:
 * <ol>
 *   <li>{@link #allowedTools()} / {@link #toolPoolLabels()} / {@link
 *       #roles()}: the conversation surface ({@code thread_get}, {@code
 *       thread_message_add}, {@code inbox_archive}, {@code task_enqueue}
 *       plus discovery and scratchpad), nothing demoted by worker-pool
 *       labels, and the {@code trillian-control} role that gates
 *       {@code task_enqueue} — the head carries it for the same reason
 *       Control does: it steers its own loop.</li>
 *   <li>{@link #renderForLlm}: contributions are foreign text with a
 *       named author — the provenance note and «…» quoting of the
 *       session-reply path, not a generic command dump — and task
 *       results carry the full {@code result}/{@code reason} (the head
 *       posts them into its thread; the truncated headline is a label,
 *       not the content).</li>
 *   <li>{@link #persistUserInputAndCollectExtras}: a conversation lives
 *       across turns. A worker's inbox is one task and may stay
 *       turn-local; a head that forgot what the counterpart asked two
 *       turns ago would answer from amnesia. Non-user steer messages are
 *       persisted as chat history (same contract as both Trillian
 *       engines, trillian-engine §10), the thread stays the record for
 *       everyone else.</li>
 * </ol>
 *
 * <p>A head never terminates by tool: it ends when its thread ends — the
 * head sweep closes it (planning/trillian-exposure.md §3.11). Natural
 * stop parks IDLE with context intact, which is exactly what a
 * conversation wants.
 */
@Component
@Slf4j
public class TrillianHeadEngine extends FrankieEngine {

    public static final String NAME = "trillian-head";
    public static final String VERSION = "0.1.0";

    /**
     * The conversation surface. Deliberately thin: a head answers, hands
     * over, concludes — it does not work (no file/exec/doc surface; that
     * is what the loop's workers are for, and handing over is one tool
     * call away). Scratchpad stays: parking an interim finding between
     * contributions is Frankie-shaped and free.
     */
    private static final Set<String> HEAD_TOOLS;

    static {
        LinkedHashSet<String> base = new LinkedHashSet<>();
        // Discovery / introspection
        base.add("tool_list");
        base.add("tool_description");
        base.add("how_do_i");
        base.add("manual_read");
        base.add("manual_list");
        base.add("recipe_describe");
        // Basics
        base.add("current_time");
        base.add("whoami");
        // Free-form notes across turns (deferred by their own flag)
        base.add("scratchpad_set");
        base.add("scratchpad_get");
        base.add("scratchpad_list");
        base.add("scratchpad_delete");
        // The conversation
        base.add("thread_get");
        base.add("thread_message_add");
        base.add("inbox_archive");
        // The handover to the loop
        base.add("task_enqueue");
        HEAD_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    /**
     * Cap on the result/reason a {@code <process-event>} renders — same
     * reasoning as every cap: the headline summary is truncated anyway,
     * the payload detail is content, but one runaway worker result must
     * not fill the prompt.
     */
    static final int MAX_EVENT_DETAIL_CHARS = 8000;

    public TrillianHeadEngine(
            ThinkProcessService thinkProcessService,
            FrankieProperties properties,
            EngineChatFactory engineChatFactory,
            LlmCallTracker llmCallTracker,
            de.mhus.vance.brain.events.StreamingProperties streamingProperties,
            ObjectMapper objectMapper,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer systemPromptComposer,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SkillTurnSupport skillTurnSupport,
            SessionService sessionService,
            de.mhus.vance.brain.context.PromptDateContextResolver promptDateContextResolver,
            de.mhus.vance.brain.prompt.ScratchpadPromptContributor scratchpadPromptContributor,
            MemoryContextLoader memoryContextLoader,
            ModelCatalog modelCatalog,
            MemoryCompactionService memoryCompactionService,
            TurnContextHandlerRegistry turnContextHandlers,
            de.mhus.vance.brain.guard.ShootyGuardService completionGuardService,
            AttachedUserMessageComposer attachedUserMessageComposer,
            de.mhus.vance.brain.prompt.ClientTurnContextResolver clientTurnContextResolver,
            SkillTriggerMatcher skillTriggerMatcher) {
        super(
                thinkProcessService,
                properties,
                engineChatFactory,
                llmCallTracker,
                streamingProperties,
                objectMapper,
                enginePromptResolver,
                systemPromptComposer,
                skillResolver,
                skillPromptComposer,
                skillTurnSupport,
                sessionService,
                promptDateContextResolver,
                scratchpadPromptContributor,
                memoryContextLoader,
                modelCatalog,
                memoryCompactionService,
                turnContextHandlers,
                completionGuardService,
                attachedUserMessageComposer,
                clientTurnContextResolver,
                skillTriggerMatcher);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Trillian Head (conversation)";
    }

    @Override
    public String description() {
        return "One conversation head per discussion thread of a multi-headed "
                + "Trillian: answers in its thread, hands work to its loop, "
                + "concludes with the conversation.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * The head's own surface, not Frankie's worker core — see the class
     * doc. Recipes pin nature, model and doctrine; membership is the
     * engine's statement of what a conversation is.
     */
    @Override
    public Set<String> allowedTools() {
        return HEAD_TOOLS;
    }

    /**
     * No worker pool: nothing is demoted by {@code WORKER} labels. The
     * thread tools carry that label (they were built as adam's worker
     * surface), and a demoted {@code thread_message_add} is a model that
     * has to answer with its answer tool behind a discovery wall.
     */
    @Override
    public Set<String> toolPoolLabels() {
        return Set.of();
    }

    /**
     * The head carries the {@code trillian-control} role: it has the same
     * legitimate claim as the human's half — handing a task to its own
     * loop ({@code task_enqueue} is role-gated on it). Everything the
     * role would additionally release ({@code user_*}) stays invisible
     * through {@link #allowedTools()} membership, which is the head's
     * actual surface.
     */
    @Override
    public Set<String> roles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    /**
     * Contributions and task results are the head's two inputs, and both
     * are foreign-authored data with structure around them — the
     * session-reply path's hygiene (provenance note, «…» quoting) is the
     * reason it exists. Everything else keeps Frankie's rendering.
     */
    @Override
    protected @Nullable String renderForLlm(SteerMessage m) {
        // Head-specific first (detailed process-events, contribution
        // commands); everything else is Frankie's to render.
        String head = renderHeadMessage(m);
        return head != null ? head : super.renderForLlm(m);
    }

    // Package-private and static: stateless string building, pinned by a
    // test — same shape as the other Trillian engines' renderers.
    static @Nullable String renderHeadMessage(SteerMessage m) {
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\"");
            // The task id the prompt tells the model to correlate on —
            // without it the head would report back the only identifier
            // it can see, the source process id.
            String taskId = payloadString(pe, TrillianInternalApi.PAYLOAD_KEY_TASK_ID);
            if (taskId != null) {
                sb.append(" taskId=\"").append(escapeAttr(taskId)).append("\"");
            }
            sb.append(" type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            // The full result/reason: the head posts this into its thread,
            // so the content matters, not just the truncated headline.
            String detail = payloadString(pe, TrillianInternalApi.PAYLOAD_KEY_RESULT);
            if (detail == null) {
                detail = payloadString(pe, TrillianInternalApi.PAYLOAD_KEY_REASON);
            }
            if (detail == null) {
                detail = payloadString(pe, "question");
            }
            if (detail != null) {
                sb.append("\n\n").append(escapeText(truncate(detail, MAX_EVENT_DETAIL_CHARS)));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ExternalCommand ec) {
            if (ec.params() != null && TrillianHeadService.COMMAND_THREAD_MESSAGE.equals(ec.command())) {
                boolean reminder = Boolean.TRUE.equals(ec.params().get(TrillianHeadService.PARAM_REMINDER));
                return "<thread-message thread=\""
                        + escapeAttr(String.valueOf(ec.params().get(TrillianHeadService.PARAM_THREAD_ID)))
                        + "\" from=\""
                        + escapeAttr(String.valueOf(ec.params().get(TrillianHeadService.PARAM_FROM)))
                        + "\""
                        + (reminder ? " reminder=\"true\"" : "")
                        + ">"
                        + ForeignPromptText.PROVENANCE_NOTE
                        + " "
                        + escapeText(ForeignPromptText.quoted(
                                String.valueOf(ec.params().get(TrillianHeadService.PARAM_PREVIEW))))
                        + "</thread-message>";
            }
            if (ec.params() != null && TrillianHeadService.COMMAND_THREAD_CONCLUDE.equals(ec.command())) {
                return "<thread-conclude thread=\""
                        + escapeAttr(String.valueOf(ec.params().get(TrillianHeadService.PARAM_THREAD_ID)))
                        + "\">This conversation is near its message limit. Post a final "
                        + "note and archive it with inbox_archive; a new conversation starts "
                        + "a fresh thread.</thread-conclude>";
            }
        }
        return null;
    }

    /**
     * A conversation lives across turns, so its inputs become history: a
     * worker's inbox is one bounded task that may stay turn-local, a
     * head's contribution from two turns ago is part of what it is
     * answering now. Same contract as both Trillian engines
     * (trillian-engine §10): persist the rendered form as USER chat log,
     * return no turn-local extras — history carries everything.
     */
    @Override
    protected List<SteerMessage> persistUserInputAndCollectExtras(
            ThinkProcessDocument process, ChatMessageService chatLog, List<SteerMessage> inbox) {
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                if (uci.content() != null && !uci.content().isBlank()) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(de.mhus.vance.api.chat.ChatRole.USER)
                            .content(uci.content())
                            .build());
                }
                continue;
            }
            String rendered = renderForLlm(m);
            if (rendered != null) {
                chatLog.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(de.mhus.vance.api.chat.ChatRole.USER)
                        .content(rendered)
                        .build());
            }
        }
        return List.of();
    }

    // ──────────────────── Helpers ────────────────────

    static @Nullable String payloadString(SteerMessage.ProcessEvent pe, String key) {
        Map<String, Object> payload = pe.payload();
        if (payload == null) {
            return null;
        }
        Object v = payload.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String truncate(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
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
