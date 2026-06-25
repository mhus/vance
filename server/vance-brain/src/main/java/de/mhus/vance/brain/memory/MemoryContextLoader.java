package de.mhus.vance.brain.memory;

import de.mhus.vance.brain.context.ReadStateService;
import de.mhus.vance.brain.rag.RagAutoInjectService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Builds a Markdown context block that conversation engines append to
 * their system message. Two layers contribute:
 *
 * <ol>
 *   <li>{@code memory.*} settings, resolved via the cascade
 *       tenant → project → think-process. Rendered as
 *       {@code key: value} lines under a {@code ## Project Memory}
 *       heading.</li>
 *   <li>An "agent" Markdown document, looked up via
 *       {@link DocumentService#lookupCascade(String, String, String)}
 *       (project → {@code _vance} → classpath default). Default path
 *       {@code agent.md}; the recipe param {@code agentDocument}
 *       overrides the path or disables the lookup entirely with
 *       {@code ""}.</li>
 * </ol>
 *
 * <p>Block returns {@code null} when both layers are empty — engines
 * skip the append in that case.
 */
@Service
@Slf4j
public class MemoryContextLoader {

    /** Settings under this prefix are surfaced into the prompt context. */
    public static final String MEMORY_PREFIX = "memory.";

    /** Recipe param key for overriding the default agent-document path. */
    public static final String AGENT_DOC_PARAM = "agentDocument";

    /** Default agent-document path looked up via the document cascade. */
    public static final String DEFAULT_AGENT_DOC_PATH = "agent.md";

    /**
     * Recipe param flag (typically set in a profile-block) that opts in
     * to splicing the client-uploaded agent doc into the memory block.
     * Off by default — only foot-style profiles turn it on.
     */
    public static final String USE_CLIENT_AGENT_DOC_PARAM = "useClientAgentDoc";

    private static final String MEMORY_HEADING = "## Project Memory";

    /** Key prefix for the in-process read-state cache. Per
     *  {@code planning/brain-context-assembler.md} §3 the {@code MEMORY:}
     *  namespace covers abstract context entries that aren't a single
     *  file or document — agent docs from the cascade live here. */
    static final String AGENT_DOC_KEY_PREFIX = "MEMORY:agent-doc:";
    static final String CLIENT_AGENT_DOC_KEY_PREFIX = "MEMORY:client-agent-doc:";

    /** Stub line the LLM sees when an auto-attachment was already shown
     *  in this process with the same content hash. Brief enough not to
     *  poison the cache marker; explicit enough that the model knows
     *  to scroll back for the actual content. */
    static final String DEDUP_AGENT_STUB =
            "(Agent notes unchanged from an earlier turn — full content is in this conversation's history.)";
    static final String DEDUP_CLIENT_AGENT_STUB =
            "(Client-supplied agent notes unchanged from an earlier turn — see history.)";

    private final SettingService settingService;
    private final SessionService sessionService;
    private final DocumentService documentService;
    private final LanguageResolver languageResolver;
    private final ReadStateService readStateService;
    private final ObjectProvider<RagAutoInjectService> ragAutoInjectProvider;
    private final MemoryService memoryService;
    private final ThinkProcessService thinkProcessService;

    public MemoryContextLoader(
            SettingService settingService,
            SessionService sessionService,
            DocumentService documentService,
            LanguageResolver languageResolver,
            ReadStateService readStateService,
            ObjectProvider<RagAutoInjectService> ragAutoInjectProvider,
            MemoryService memoryService,
            ThinkProcessService thinkProcessService) {
        this.settingService = settingService;
        this.sessionService = sessionService;
        this.documentService = documentService;
        this.languageResolver = languageResolver;
        this.readStateService = readStateService;
        this.ragAutoInjectProvider = ragAutoInjectProvider;
        this.memoryService = memoryService;
        this.thinkProcessService = thinkProcessService;
    }

    /**
     * Backward-compatible overload — no user-query, so the RAG
     * auto-inject layer is a no-op. Callers without an inbox query
     * (background triggers, system-only turns) keep using this; engines
     * that have a fresh user query should call
     * {@link #composeBlock(ThinkProcessDocument, String)}.
     */
    public @Nullable String composeBlock(ThinkProcessDocument process) {
        return composeBlock(process, null);
    }

    /**
     * Returns the Markdown block to append to the system prompt, or
     * {@code null} when no layer (memory settings, agent document,
     * project RAG) yielded any content for this turn.
     *
     * <p>{@code userQuery} is the text the engine wants the RAG layer
     * to embed for similarity search — typically the latest user-chat
     * input of this turn. When it's {@code null} or blank the RAG
     * layer skips. RAG auto-inject is opt-in per recipe param
     * {@code rag.autoInject}; see {@link RagAutoInjectService}.
     */
    public @Nullable String composeBlock(
            ThinkProcessDocument process, @Nullable String userQuery) {
        if (process == null || process.getTenantId() == null
                || process.getTenantId().isBlank()) {
            return null;
        }
        String projectId = resolveProjectId(process);
        StringBuilder sb = new StringBuilder();

        appendLanguages(sb, process, projectId);
        appendMemorySettings(sb, process, projectId);
        appendAgentDocument(sb, process, projectId);
        appendClientAgentDoc(sb, process);
        appendArchivedChatSummary(sb, process);
        appendParentContext(sb, process);
        appendRagAutoInject(sb, process, userQuery);

        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * When this process is a worker spawned from another process
     * ({@code parentProcessId != null}), surfaces a short Parent-Context
     * block so the worker isn't completely blind to the upstream mission.
     * See {@code planning/memory-compaction.md} §6.
     *
     * <p>Two confidentiality tiers:
     *
     * <ul>
     *   <li><b>Same project</b> — Parent identity (name / recipe / engine /
     *       title) PLUS Parent's active {@code ARCHIVED_CHAT}-summary if
     *       one exists. The summary is the parent's compacted
     *       conversation so far, which gives the worker enough context
     *       to act without re-asking for goals.</li>
     *   <li><b>Cross-project</b> ({@code allowsCrossProjectSpawn=true}
     *       engines like Trillian) — only the identity block; the
     *       parent-summary stays in the parent's project to avoid
     *       leaking content across project boundaries. Workers needing
     *       more context should be passed an explicit {@code goal}
     *       parameter at spawn time.</li>
     * </ul>
     *
     * <p>Missing parent (orphaned {@code parentProcessId}) → graceful
     * skip; never breaks the prompt build.
     */
    private void appendParentContext(
            StringBuilder sb, ThinkProcessDocument process) {
        String parentId = process.getParentProcessId();
        if (parentId == null || parentId.isBlank()) return;
        ThinkProcessDocument parent;
        try {
            parent = thinkProcessService.findById(parentId).orElse(null);
        } catch (RuntimeException e) {
            log.debug("appendParentContext: lookup failed for parent='{}': {}",
                    parentId, e.toString());
            return;
        }
        if (parent == null) return;

        StringBuilder block = new StringBuilder();
        block.append("## Parent Context\n\n");
        block.append("Parent process: `").append(safe(parent.getName()))
                .append("` (recipe: ").append(safe(parent.getRecipeName()))
                .append(", engine: ").append(safe(parent.getThinkEngine()))
                .append(")\n");
        if (parent.getTitle() != null && !parent.getTitle().isBlank()) {
            block.append("Parent mission: ").append(parent.getTitle()).append("\n");
        }

        boolean sameProject = process.getProjectId() != null
                && process.getProjectId().equals(parent.getProjectId());
        if (sameProject && parent.getId() != null) {
            List<MemoryDocument> parentSummaries =
                    memoryService.activeByProcessAndKind(
                            parent.getTenantId(), parent.getId(),
                            MemoryKind.ARCHIVED_CHAT);
            if (!parentSummaries.isEmpty()) {
                MemoryDocument latest = parentSummaries.getLast();
                String body = latest.getContent();
                if (body != null && !body.isBlank()) {
                    block.append("\n### Parent Conversation Summary\n\n");
                    block.append(body);
                    if (!body.endsWith("\n")) block.append('\n');
                }
            }
        }
        // cross-project: identity only, no summary content (confidentiality)

        if (sb.length() > 0) sb.append('\n');
        sb.append(block);
    }

    private static String safe(@Nullable String s) {
        return s == null || s.isBlank() ? "?" : s;
    }

    /**
     * Replays the most-recent active {@link MemoryKind#ARCHIVED_CHAT}
     * summary into the memory block so the LLM sees the gist of the
     * conversation that was rolled out of {@code activeHistory()} by
     * {@link MemoryCompactionService#compact}. Without this, every
     * HARD-trigger compaction would *de facto* erase the prior
     * conversation from the LLM's view — the summary would sit unused
     * in Mongo. See {@code planning/memory-compaction.md} §4.
     *
     * <p>Picks the latest active row from
     * {@link MemoryService#activeByProcessAndKind} (sorted ASC by
     * {@code createdAt}, so {@code getLast()} is the newest). Hierarchical
     * compaction uses {@code supersede()} to retire older summaries, so
     * in steady state there is at most one active row — the defensive
     * "pick last" handles a write-write race transparently.
     */
    private void appendArchivedChatSummary(
            StringBuilder sb, ThinkProcessDocument process) {
        String tenantId = process.getTenantId();
        String processId = process.getId();
        if (tenantId == null || tenantId.isBlank()) return;
        if (processId == null || processId.isBlank()) return;
        List<MemoryDocument> active = memoryService.activeByProcessAndKind(
                tenantId, processId, MemoryKind.ARCHIVED_CHAT);
        if (active.isEmpty()) return;
        MemoryDocument summary = active.getLast();
        String body = summary.getContent();
        if (body == null || body.isBlank()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append("## Earlier Conversation (compacted)\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
    }

    /**
     * Splices the project-RAG auto-inject block in at the end of the
     * memory context. Engine-agnostic: every engine that hands a
     * {@code userQuery} to {@link #composeBlock(ThinkProcessDocument, String)}
     * gets the same treatment, no per-engine wiring. Off by default;
     * opted in via recipe-param {@code rag.autoInject} (cascade-
     * override {@code rag.autoInject.enabled}).
     */
    private void appendRagAutoInject(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String userQuery) {
        if (userQuery == null || userQuery.isBlank()) return;
        RagAutoInjectService rag = ragAutoInjectProvider.getIfAvailable();
        if (rag == null) return;
        String block = rag.composeBlock(process, userQuery);
        if (block == null || block.isBlank()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(block);
    }

    /**
     * Renders a {@code ## Languages} block when at least one of
     * {@code chat.language} / {@code content.language} is set anywhere
     * in the cascade. Both keys are read independently — chat picks up
     * the user-layer too, content does not (see {@link LanguageResolver}).
     *
     * <p>Skipped when both keys come back null. The fallback default
     * stays implicit ({@link LanguageResolver#DEFAULT_LANGUAGE}) — no
     * point telling the LLM "respond in en" if the operator hasn't
     * actually picked anything.
     */
    private void appendLanguages(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String projectId) {
        // userId for the chat-language cascade comes from the session
        // (ThinkProcessDocument doesn't carry it directly — workers
        // inherit it via the session binding).
        @Nullable String userId = null;
        if (process.getSessionId() != null && !process.getSessionId().isBlank()) {
            userId = sessionService.findBySessionId(process.getSessionId())
                    .map(SessionDocument::getUserId)
                    .orElse(null);
        }
        String block = languageResolver.formatLanguageBlock(
                process.getTenantId(), userId, projectId, process.getId());
        if (block == null || block.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(block);
    }

    private void appendMemorySettings(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String projectId) {
        Map<String, String> entries = settingService.findByPrefixCascade(
                process.getTenantId(),
                projectId,
                process.getId(),
                MEMORY_PREFIX);
        if (entries.isEmpty()) return;
        sb.append(MEMORY_HEADING).append('\n');
        entries.forEach((key, value) -> {
            String stripped = key.startsWith(MEMORY_PREFIX)
                    ? key.substring(MEMORY_PREFIX.length())
                    : key;
            if (stripped.isBlank()) return;
            sb.append(stripped).append(": ")
                    .append(value == null ? "" : value).append('\n');
        });
    }

    private void appendAgentDocument(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String projectId) {
        if (projectId == null) return;
        @Nullable String path = resolveAgentPath(process);
        if (path == null) return;
        documentService.lookupCascade(process.getTenantId(), projectId, path)
                .ifPresent(result -> {
                    String content = result.content();
                    if (content == null || content.isBlank()) {
                        // Empty agent doc — render the heading only.
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(agentHeading(result)).append('\n');
                        return;
                    }
                    String hash = ReadStateService.hashContent(content);
                    String key = AGENT_DOC_KEY_PREFIX + projectId + ":" + path;
                    if (readStateService.hasFresh(process, key, hash)) {
                        // Same content already shown earlier — append a
                        // brief stub instead of the full body. Saves
                        // tokens + keeps the prompt-cache marker stable
                        // across turns. Plan §5.2.
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(agentHeading(result)).append('\n')
                                .append(DEDUP_AGENT_STUB).append('\n');
                        return;
                    }
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(agentHeading(result)).append('\n')
                            .append(content.trim()).append('\n');
                    // Best-effort record — failures don't break the
                    // prompt build (the doc is still inlined this turn).
                    try {
                        readStateService.recordRead(process, key, hash,
                                /*partialView*/ false,
                                (long) content.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8).length);
                    } catch (RuntimeException e) {
                        log.debug("ReadState recordRead failed for {}: {}", key, e.toString());
                    }
                });
    }

    /**
     * Splices the session's {@code clientAgentDoc} (uploaded by the
     * foot client right after bind) into the prompt — but only when
     * the active recipe's profile-block has opted in via
     * {@code params.useClientAgentDoc=true}. Quiet no-op otherwise:
     * for web/mobile the upload doesn't happen, and for foot recipes
     * that don't ask for it the flag is absent.
     */
    private void appendClientAgentDoc(StringBuilder sb, ThinkProcessDocument process) {
        if (!isClientAgentDocEnabled(process)) return;
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;
        sessionService.findBySessionId(sessionId).ifPresent(session -> {
            String content = session.getClientAgentDoc();
            if (content == null || content.isBlank()) return;
            String path = session.getClientAgentDocPath();
            String heading = "## Agent Notes (from client: "
                    + (path == null || path.isBlank() ? "agent.md" : path) + ")";

            String hash = ReadStateService.hashContent(content);
            String key = CLIENT_AGENT_DOC_KEY_PREFIX + sessionId;
            if (readStateService.hasFresh(process, key, hash)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(heading).append('\n')
                        .append(DEDUP_CLIENT_AGENT_STUB).append('\n');
                return;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(heading).append('\n').append(content.trim()).append('\n');
            try {
                readStateService.recordRead(process, key, hash,
                        /*partialView*/ false,
                        (long) content.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8).length);
            } catch (RuntimeException e) {
                log.debug("ReadState recordRead failed for {}: {}", key, e.toString());
            }
        });
    }

    private static boolean isClientAgentDocEnabled(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return false;
        Object raw = params.get(USE_CLIENT_AGENT_DOC_PARAM);
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase();
            return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
        }
        return false;
    }

    /**
     * Resolves the agent-document path:
     * <ul>
     *   <li>recipe param {@code agentDocument} not set → use the default</li>
     *   <li>set to a non-blank string → use that string verbatim</li>
     *   <li>set to {@code null} or {@code ""} → disabled, return {@code null}</li>
     * </ul>
     */
    private @Nullable String resolveAgentPath(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null || !params.containsKey(AGENT_DOC_PARAM)) {
            return DEFAULT_AGENT_DOC_PATH;
        }
        Object raw = params.get(AGENT_DOC_PARAM);
        if (raw == null) return null;
        String v = raw.toString().trim();
        return v.isEmpty() ? null : v;
    }

    private static String agentHeading(LookupResult result) {
        String path = result.path();
        return switch (result.source()) {
            case PROJECT -> "## Agent Notes (" + path + ")";
            case VANCE -> "## Agent Notes (from _vance: " + path + ")";
            case RESOURCE -> "## Agent Notes (system default: " + path + ")";
        };
    }

    private @Nullable String resolveProjectId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }
}
