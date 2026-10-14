package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.frankie.FrankieEngine;
import de.mhus.vance.brain.frankie.FrankieProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.ForeignPromptText;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.skill.SkillTurnSupport;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Collections;
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
 * contribution, answering in its thread, doing the reading and research a
 * conversation needs itself.
 *
 * <p><b>Why an engine and not a recipe on a chat engine.</b> The same
 * argument as {@link TrillianWorkerEngine}, from the other side: a head
 * is a worker's loop shape (drain, model, tools, natural stop — park
 * IDLE, no termination tool) with a conversation surface instead of a
 * work surface. The tools are not the recipe's to pick: {@code
 * thread_message_add} is the head's <em>primary</em> answer channel (a
 * worker-pool classification would demote it to deferred, where a model
 * that must answer may not even find the tool). {@code allowedTools()} is
 * the engine's own surface; a recipe trims prompts and models, not
 * membership.
 *
 * <p><b>What a head may do.</b> Work with the documents of its hub (read
 * and write, under the account's rights — WRITER, so never {@code _vance/}),
 * research the web and the research sources, and answer. Not: run code, or
 * reach other processes. The worker pool is deliberately not
 * taken: it carries {@code process_list}/{@code process_stop}/{@code
 * process_steer}, {@code inbox_list} and the wakeup tools, and the head
 * lives in the loop session next to the loop and every other head — a
 * counterpart must not be able to list, steer or stop them, nor read the
 * other conversations. And the head is {@link #confinedToOwnProject()
 * confined to its hub}: the Trillian holds ADMIN on its human's project, and
 * a counterpart must not read that through a {@code projectId} argument.
 *
 * <p><b>What changes.</b> Four seams, none of Frankie's protocol:
 * <ol>
 *   <li>{@link #allowedTools()} / {@link #toolPoolLabels()} / {@link
 *       #roles()}: the conversation surface ({@code thread_get}, {@code
 *       thread_message_add}, {@code inbox_archive}, {@code task_enqueue}),
 *       the hub's documents, research, discovery and
 *       scratchpad — nothing demoted by worker-pool labels, and the {@code trillian-task-requester} role that gates
 *       {@code task_enqueue} — the one claim a head shares with Control.
 *       Not the control role itself, which would release the
 *       {@code user_*} steering tools.</li>
 *   <li>{@link #defaultPromptPath()} / {@link #systemPromptAddendum}: the
 *       head's own engine prompt ({@code trillian-head-prompt.md}) instead
 *       of Frankie's worker prompt, plus the Nature's head addendum (the
 *       shared persona, then what the Nature says about itself).</li>
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
     * The head's surface: the conversation, the hub's documents, and
     * research. Explicit names, no worker pool — see the
     * class doc for what the pool would bring along. Scratchpad stays:
     * parking an interim finding between contributions is Frankie-shaped
     * and free.
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
        base.add("tool_result_read");
        // Basics
        base.add("current_time");
        base.add("whoami");
        // Free-form notes across turns (deferred by their own flag)
        base.add("scratchpad_set");
        base.add("scratchpad_get");
        base.add("scratchpad_list");
        base.add("scratchpad_delete");
        // Documents of the hub — confined to the hub
        // (confinedToOwnProject), id lookups included.
        base.add("doc_list");
        base.add("doc_list_folders");
        base.add("doc_list_in_folder");
        base.add("doc_list_by_tag");
        base.add("doc_find");
        base.add("doc_count");
        base.add("doc_grep");
        base.add("doc_grep_path");
        base.add("doc_read");
        base.add("doc_read_lines");
        base.add("doc_head_tail");
        base.add("doc_info");
        base.add("doc_summary");
        base.add("pdf_read");
        // Write side — the same set the Trillian's workers carry. Rights are
        // the account's (WRITER on its hub: documents yes, _vance/ no).
        base.add("doc_write");
        base.add("doc_edit");
        base.add("doc_append");
        base.add("doc_replace_lines");
        // Research
        base.add("web_fetch");
        base.add("web_search");
        base.add("research_search");
        base.add("research_investigate");
        base.add("research_rich");
        base.add("research_providers");
        // The conversation
        base.add("thread_get");
        base.add("thread_message_add");
        base.add("inbox_archive");
        // The handover to the loop — for work that runs long or belongs to
        // the Trillian's own work
        base.add("task_enqueue");
        HEAD_TOOLS = Collections.unmodifiableSet(base);
    }

    /**
     * Cap on the result/reason a {@code <process-event>} renders — same
     * reasoning as every cap: the headline summary is truncated anyway,
     * the payload detail is content, but one runaway worker result must
     * not fill the prompt.
     */
    static final int MAX_EVENT_DETAIL_CHARS = 8000;

    /**
     * The head's engine prompt — the conversation doctrine. Its own
     * document, not Frankie's: Frankie's prompt says "you are a focused
     * worker, not a chat partner, don't ask questions", the opposite of a
     * head, and a recipe {@code promptPrefix} can only append to it.
     */
    static final String PROMPT_PATH = "_vance/prompts/trillian-head-prompt.md";

    private static final String FALLBACK_PROMPT =
            "You are one conversation of this Trillian. Read each contribution with "
                    + "thread_get and answer it in the thread with thread_message_add — "
                    + "plain text here reaches nobody. Do the work yourself: you can read and "
                    + "write this Trillian's documents and search the web. Hand work that runs "
                    + "long to your loop with task_enqueue and post its result into the thread. When the matter is settled, post a closing "
                    + "line and archive the thread with inbox_archive.";

    private final TrillianNatureRegistry natureRegistry;

    public TrillianHeadEngine(
            ThinkProcessService thinkProcessService,
            FrankieProperties properties,
            EngineChatFactory engineChatFactory,
            LlmCallTracker llmCallTracker,
            StreamingProperties streamingProperties,
            ObjectMapper objectMapper,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer systemPromptComposer,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SkillTurnSupport skillTurnSupport,
            SessionService sessionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            MemoryContextLoader memoryContextLoader,
            ModelCatalog modelCatalog,
            MemoryCompactionService memoryCompactionService,
            TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService completionGuardService,
            AttachedUserMessageComposer attachedUserMessageComposer,
            ClientTurnContextResolver clientTurnContextResolver,
            SkillTriggerMatcher skillTriggerMatcher,
            TrillianNatureRegistry natureRegistry) {
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
        this.natureRegistry = natureRegistry;
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
                + "Trillian: answers in its thread, works with the hub's documents and "
                + "researches itself, concludes with the conversation.";
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
     * The head may hand a task to its own loop — the one claim it shares
     * with the human's Control ({@code task_enqueue} is gated on
     * {@link TrillianInternalApi#ROLE_TASK_REQUESTER}). Deliberately not
     * {@code trillian-control}: that role releases the {@code user_*}
     * steering tools, and a head must not get them even through a recipe's
     * {@code allowedToolsAdd}.
     */
    @Override
    public Set<String> roles() {
        return Set.of(TrillianInternalApi.ROLE_TASK_REQUESTER);
    }

    /**
     * The head talks to an external counterpart with the Trillian's
     * rights, and the Trillian holds ADMIN on its human's project: every
     * project-addressed access stays in the head's hub.
     */
    @Override
    public boolean confinedToOwnProject() {
        return true;
    }

    @Override
    protected String defaultPromptPath() {
        return PROMPT_PATH;
    }

    @Override
    protected String engineFallbackPrompt() {
        return FALLBACK_PROMPT;
    }

    /**
     * Who is talking: the Nature's head addendum — the shared persona read
     * off the loop plus whatever the Nature says about itself in a
     * conversation ({@code TrillianNature#headSystemAddendum}).
     */
    @Override
    protected @Nullable String systemPromptAddendum(ThinkProcessDocument process) {
        try {
            return natureRegistry
                    .resolve(TrillianSessionBootstrapper.readNature(process))
                    .headSystemAddendum(process);
        } catch (RuntimeException e) {
            log.warn("Trillian head id='{}': nature addendum failed: {}", process.getId(), e.toString());
            return null;
        }
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
                            .role(ChatRole.USER)
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
                        .role(ChatRole.USER)
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
