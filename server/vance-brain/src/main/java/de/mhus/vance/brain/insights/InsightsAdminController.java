package de.mhus.vance.brain.insights;

import de.mhus.vance.api.addon.AddonInsightDto;
import de.mhus.vance.api.insights.ActiveSkillInsightsDto;
import de.mhus.vance.api.insights.BrainPodInsightsDto;
import de.mhus.vance.api.insights.CacheStatsDto;
import de.mhus.vance.api.insights.ChatMessageInsightsDto;
import de.mhus.vance.api.insights.EffectiveRecipeDto;
import de.mhus.vance.api.insights.EffectiveToolDto;
import de.mhus.vance.api.insights.MarvinNodeInsightsDto;
import de.mhus.vance.api.insights.MemoryInsightsDto;
import de.mhus.vance.api.insights.PendingMessageInsightsDto;
import de.mhus.vance.api.insights.PrakRunInsightsDto;
import de.mhus.vance.api.insights.SessionClientToolsDto;
import de.mhus.vance.api.insights.SessionInsightsDto;
import de.mhus.vance.api.insights.ThinkProcessInsightsDto;
import de.mhus.vance.api.insights.ZarniwoopInsightsDto;
import de.mhus.vance.brain.zarniwoop.ZarniwoopInsightsService;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.addon.AddonInsightsService;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.BuiltInToolSource;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.workspace.access.PodForwarder;
import de.mhus.vance.brain.workspace.access.ProjectPodKey;
import de.mhus.vance.brain.workspace.access.WorkspaceAccessProperties;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.api.llmtrace.LlmTraceDto;
import de.mhus.vance.api.llmtrace.LlmTraceListResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.prak.audit.PrakRunRecord;
import de.mhus.vance.shared.prak.audit.PrakRunService;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only inspection of sessions, think-processes, chat history,
 * memory entries and Marvin task-trees. Diagnostic UI surface — no
 * mutating endpoints belong here.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim before requests reach this controller.
 * Each per-record endpoint additionally checks that the record's own
 * {@code tenantId} matches the path tenant — Mongo ids are global, so
 * this prevents cross-tenant probing via hand-crafted ids.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class InsightsAdminController {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final MarvinNodeService marvinNodeService;
    private final LlmTraceService llmTraceService;
    private final EngineMessageService engineMessageService;
    private final RecipeLoader recipeLoader;
    private final ServerToolService serverToolService;
    private final ServerToolLoader serverToolLoader;
    private final BuiltInToolSource builtInToolSource;
    private final ClientToolRegistry clientToolRegistry;
    private final PodForwarder podForwarder;
    private final WorkspaceAccessProperties workspaceAccessProperties;
    private final ClusterService clusterService;
    private final PrakRunService prakRunService;
    private final AddonInsightsService addonInsightsService;
    private final ZarniwoopInsightsService zarniwoopInsightsService;
    private final RequestAuthority authority;
    private final ObjectMapper objectMapper;

    // ─── Sessions ──────────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public List<SessionInsightsDto> listSessions(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "userId", required = false) @Nullable String userId,
            @RequestParam(value = "status", required = false) @Nullable String status,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        List<SessionDocument> sessions;
        if (userId != null && !userId.isBlank() && projectId != null && !projectId.isBlank()) {
            sessions = sessionService.listForUserAndProject(tenant, userId, projectId);
        } else if (userId != null && !userId.isBlank()) {
            sessions = sessionService.listForUser(tenant, userId);
        } else if (projectId != null && !projectId.isBlank()) {
            sessions = sessionService.listForProject(tenant, projectId);
        } else {
            sessions = sessionService.listForTenant(tenant);
        }

        return sessions.stream()
                .filter(s -> status == null || status.isBlank()
                        || (s.getStatus() != null && status.equalsIgnoreCase(s.getStatus().name())))
                .sorted(Comparator
                        .comparing(SessionDocument::getLastActivityAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> toListDto(tenant, s))
                .toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionInsightsDto getSession(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        SessionDocument doc = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        authority.enforce(httpRequest,
                new Resource.Session(tenant, doc.getProjectId(), doc.getSessionId()), Action.ADMIN);
        return toListDto(tenant, doc);
    }

    @GetMapping("/sessions/{sessionId}/processes")
    public List<ThinkProcessInsightsDto> listProcesses(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        // Verify the session exists in this tenant before walking processes —
        // otherwise a wrong sessionId silently returns [].
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        authority.enforce(httpRequest,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()), Action.ADMIN);

        return thinkProcessService.findBySession(tenant, sessionId).stream()
                .sorted(Comparator
                        .comparing(ThinkProcessDocument::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toDto)
                .toList();
    }

    /**
     * Stream every Mongo record that belongs to this session as a single
     * JSON-lines (NDJSON) file. One header line ({@code session_meta})
     * followed by every {@code process}, {@code message}, {@code memory},
     * {@code llm_trace}, {@code marvin_node} and {@code prak_run} row
     * sorted by timestamp ascending. Used by ops / users to ship a
     * self-contained snapshot for external problem analysis.
     *
     * <p>Allowed for running sessions too — Mongo reads are non-locking
     * and a sub-second snapshot drift is acceptable for diagnostics.
     */
    @GetMapping(value = "/sessions/{sessionId}/export.jsonl",
            produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> exportSession(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {

        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        authority.enforce(httpRequest,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.ADMIN);

        // Collect every process in the session, then everything keyed by
        // those process ids. Each per-process service call is bounded —
        // even a long-running session is at most a few processes.
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(tenant, sessionId);

        List<ChatMessageDocument> chat = new ArrayList<>();
        List<MemoryDocument> memory = new ArrayList<>();
        List<LlmTraceDocument> traces = new ArrayList<>();
        List<MarvinNodeDocument> marvinNodes = new ArrayList<>();
        List<PrakRunRecord> prakRuns = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            String pid = p.getId();
            if (pid == null) continue;
            chat.addAll(chatMessageService.history(tenant, sessionId, pid));
            memory.addAll(memoryService.listByProcess(tenant, pid));
            // listByProcess is paginated (cap 200/page); walk pages until
            // exhausted so we don't silently truncate a chatty session.
            int page = 0;
            while (true) {
                org.springframework.data.domain.Page<LlmTraceDocument> chunk =
                        llmTraceService.listByProcess(tenant, pid, page, 200);
                traces.addAll(chunk.getContent());
                if (chunk.getNumber() + 1 >= chunk.getTotalPages() || chunk.isEmpty()) break;
                page++;
            }
            if ("marvin".equalsIgnoreCase(p.getThinkEngine())) {
                marvinNodes.addAll(marvinNodeService.listAll(pid));
            }
            prakRuns.addAll(prakRunService.listByProcess(tenant, pid, PrakRunService.MAX_LIST_LIMIT));
        }

        SessionExportEmitter.ExportData data = new SessionExportEmitter.ExportData(
                session, processes, chat, memory, traces, marvinNodes, prakRuns);

        String filename = buildExportFilename(sessionId, Instant.now());
        StreamingResponseBody body = (OutputStream out) -> SessionExportEmitter.write(out, objectMapper, data);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** Build {@code session-{id}-{utc-ts-with-dashes}.jsonl}. */
    static String buildExportFilename(String sessionId, Instant now) {
        // ISO-8601 UTC with colons replaced by dashes so the filename is
        // safe on every platform (Windows in particular rejects ':' in
        // filenames).
        String ts = DateTimeFormatter.ISO_INSTANT
                .format(now.atOffset(ZoneOffset.UTC))
                .replace(':', '-');
        String safeId = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return "session-" + safeId + "-" + ts + ".jsonl";
    }

    // ─── Processes ─────────────────────────────────────────────────────────

    @GetMapping("/processes/{processId}")
    public ThinkProcessInsightsDto getProcess(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        return toDto(process);
    }

    @GetMapping("/processes/{processId}/chat")
    public List<ChatMessageInsightsDto> listChat(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        List<ChatMessageDocument> messages = chatMessageService.history(
                tenant, process.getSessionId(), process.getId());
        return messages.stream()
                .map(InsightsAdminController::toDto)
                .toList();
    }

    @GetMapping("/processes/{processId}/memory")
    public List<MemoryInsightsDto> listMemory(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        List<MemoryDocument> memories = memoryService.listByProcess(tenant, process.getId());
        return memories.stream()
                .sorted(Comparator
                        .comparing(MemoryDocument::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(InsightsAdminController::toDto)
                .toList();
    }

    /**
     * Prak-run audit-trail for one process — drives the Insights
     * "Prak Runs" tab. Empty list when {@code vance.prak.sideChannelEnabled}
     * is off (no runs ever written) or the process never compacted.
     * Newest first, capped at 100 rows.
     */
    @GetMapping("/processes/{processId}/prak-runs")
    public List<PrakRunInsightsDto> listPrakRuns(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        return prakRunService.listByProcess(tenant, process.getId(), 100).stream()
                .map(InsightsAdminController::toDto)
                .toList();
    }

    @GetMapping("/processes/{processId}/marvin-tree")
    public List<MarvinNodeInsightsDto> listMarvinTree(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        // Tree is only meaningful for marvin processes; for other engines we
        // simply return an empty list rather than 404, so the client can
        // unconditionally fetch the tab without conditional logic.
        return marvinNodeService.listAll(process.getId()).stream()
                .map(InsightsAdminController::toDto)
                .toList();
    }

    /**
     * Paginated LLM-trace history for one process — drives the
     * Insights "LLM Trace" tab. Empty list when {@code tracing.llm}
     * was off when the process ran (no rows ever written) or after
     * MongoDB's TTL evicted them. Newest entries first; the UI groups
     * by {@code turnId} client-side.
     *
     * @param page  zero-based, default 0
     * @param size  page size, capped server-side at 200
     */
    @GetMapping("/processes/{processId}/llm-traces")
    public LlmTraceListResponse listLlmTraces(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        org.springframework.data.domain.Page<LlmTraceDocument> result =
                llmTraceService.listByProcess(tenant, process.getId(), page, size);
        List<LlmTraceDto> items = result.getContent().stream()
                .map(InsightsAdminController::toDto)
                .toList();
        return LlmTraceListResponse.builder()
                .items(items)
                .page(result.getNumber())
                .pageSize(result.getSize())
                .totalCount(result.getTotalElements())
                .build();
    }

    /**
     * Aggregated Anthropic cache statistics for one process: input /
     * output / cache-creation / cache-read tokens summed over every
     * OUTPUT trace row, plus a hit-rate fraction. Drives the Insights
     * "is caching paying off here?" view. See
     * {@code specification/prompt-caching.md} §10.4.
     */
    @GetMapping("/processes/{processId}/cache-stats")
    public CacheStatsDto getCacheStats(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = loadProcess(tenant, processId);
        authority.enforce(httpRequest, processResource(process), Action.ADMIN);
        LlmTraceService.CacheStatsAccumulator acc =
                llmTraceService.cacheStatsByProcess(tenant, process.getId());
        return CacheStatsDto.builder()
                .roundTrips(acc.roundTrips())
                .inputTokens(acc.inputTokens())
                .outputTokens(acc.outputTokens())
                .cacheCreationInputTokens(acc.cacheCreationInputTokens())
                .cacheReadInputTokens(acc.cacheReadInputTokens())
                .hitRate(acc.hitRate())
                .build();
    }

    // ─── Authorization helpers ─────────────────────────────────────────────

    private ThinkProcessDocument loadProcess(String tenant, String processId) {
        return thinkProcessService.findById(processId)
                .filter(p -> tenant.equals(p.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Process '" + processId + "' not found"));
    }

    private static Resource.ThinkProcess processResource(ThinkProcessDocument p) {
        return new Resource.ThinkProcess(
                p.getTenantId(), p.getProjectId(), p.getSessionId(), p.getId() == null ? "" : p.getId());
    }

    // ─── Mapping helpers ───────────────────────────────────────────────────

    private SessionInsightsDto toListDto(String tenant, SessionDocument s) {
        Integer count = null;
        // Counting is cheap (collection-scan one tenant+sessionId index hit) but
        // we skip it for closed sessions since they can't grow further.
        try {
            count = thinkProcessService.findBySession(tenant, s.getSessionId()).size();
        } catch (RuntimeException e) {
            log.debug("processCount lookup failed for session {}: {}",
                    s.getSessionId(), e.toString());
        }
        return SessionInsightsDto.builder()
                .id(s.getId())
                .sessionId(s.getSessionId())
                .userId(s.getUserId())
                .projectId(s.getProjectId())
                .displayName(s.getDisplayName())
                .profile(s.getProfile() == null ? "" : s.getProfile())
                .clientVersion(s.getClientVersion())
                .clientName(s.getClientName())
                .status(s.getStatus() == null ? "" : s.getStatus().name())
                .boundConnectionId(s.getBoundConnectionId())
                .chatProcessId(s.getChatProcessId())
                .createdAt(s.getCreatedAt())
                .lastActivityAt(s.getLastActivityAt())
                .firstUserMessage(s.getFirstUserMessage())
                .lastMessagePreview(s.getLastMessagePreview())
                .lastMessageRole(s.getLastMessageRole())
                .lastMessageAt(s.getLastMessageAt())
                .processCount(count)
                .build();
    }

    private ThinkProcessInsightsDto toDto(ThinkProcessDocument p) {
        return ThinkProcessInsightsDto.builder()
                .id(p.getId())
                .sessionId(p.getSessionId())
                .name(p.getName())
                .title(p.getTitle())
                .thinkEngine(p.getThinkEngine())
                .thinkEngineVersion(p.getThinkEngineVersion())
                .goal(p.getGoal())
                .recipeName(p.getRecipeName())
                .status(p.getStatus() == null ? "" : p.getStatus().name())
                .parentProcessId(p.getParentProcessId())
                .engineParams(new LinkedHashMap<>(p.getEngineParams()))
                .activeSkills(p.getActiveSkills().stream()
                        .map(InsightsAdminController::toDto)
                        .toList())
                .pendingMessages(engineMessageService.drainInbox(p.getId()).stream()
                        .map(InsightsAdminController::toDto)
                        .toList())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private static ActiveSkillInsightsDto toDto(ActiveSkillRefEmbedded a) {
        return ActiveSkillInsightsDto.builder()
                .name(a.getName())
                .resolvedFromScope(a.getResolvedFromScope() == null
                        ? null
                        : a.getResolvedFromScope().name())
                .oneShot(a.isOneShot())
                .fromRecipe(a.isFromRecipe())
                .activatedAt(a.getActivatedAt())
                .build();
    }

    private static PendingMessageInsightsDto toDto(EngineMessageDocument m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotNull(payload, "content", m.getContent());
        putIfNotNull(payload, "messageId", m.getMessageId());
        putIfNotNull(payload, "senderProcessId", m.getSenderProcessId());
        putIfNotNull(payload, "deliveredAt", m.getDeliveredAt());
        putIfNotNull(payload, "drainedAt", m.getDrainedAt());
        putIfNotNull(payload, "sourceProcessId", m.getSourceProcessId());
        putIfNotNull(payload, "eventType", m.getEventType());
        putIfNotNull(payload, "toolCallId", m.getToolCallId());
        putIfNotNull(payload, "toolName", m.getToolName());
        putIfNotNull(payload, "toolStatus", m.getToolStatus());
        putIfNotNull(payload, "error", m.getError());
        putIfNotNull(payload, "command", m.getCommand());
        putIfNotNull(payload, "inboxItemId", m.getInboxItemId());
        putIfNotNull(payload, "inboxItemType", m.getInboxItemType());
        putIfNotNull(payload, "inboxAnswer", m.getInboxAnswer());
        putIfNotNull(payload, "sourceEddieProcessId", m.getSourceEddieProcessId());
        putIfNotNull(payload, "peerUserId", m.getPeerUserId());
        putIfNotNull(payload, "peerEventType", m.getPeerEventType());
        if (m.getPayload() != null && !m.getPayload().isEmpty()) {
            payload.put("data", m.getPayload());
        }
        return PendingMessageInsightsDto.builder()
                .type(m.getType() == null ? "" : m.getType().name())
                .at(m.getCreatedAt())
                .fromUser(m.getFromUser())
                .payload(payload)
                .build();
    }

    private static void putIfNotNull(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) map.put(key, value instanceof Enum<?> e ? e.name() : value);
    }

    private static ChatMessageInsightsDto toDto(ChatMessageDocument c) {
        return ChatMessageInsightsDto.builder()
                .id(c.getId())
                .role(c.getRole())
                .content(c.getContent())
                .archivedInMemoryId(c.getArchivedInMemoryId())
                .tags(c.getTags() == null ? new ArrayList<>() : new ArrayList<>(c.getTags()))
                .createdAt(c.getCreatedAt())
                .build();
    }

    private static MemoryInsightsDto toDto(MemoryDocument m) {
        return MemoryInsightsDto.builder()
                .id(m.getId())
                .kind(m.getKind() == null ? "" : m.getKind().name())
                .title(m.getTitle())
                .content(m.getContent())
                .sourceRefs(new ArrayList<>(m.getSourceRefs()))
                .metadata(new LinkedHashMap<>(m.getMetadata()))
                .supersededByMemoryId(m.getSupersededByMemoryId())
                .supersededAt(m.getSupersededAt())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private static PrakRunInsightsDto toDto(PrakRunRecord r) {
        return PrakRunInsightsDto.builder()
                .id(r.getId())
                .runId(r.getRunId())
                .trigger(r.getTrigger())
                .windowFromTurnId(r.getWindowFromTurnId())
                .windowToTurnId(r.getWindowToTurnId())
                .windowMessages(r.getWindowMessages())
                .rawItemCount(r.getRawItemCount())
                .finalItemCount(r.getFinalItemCount())
                .droppedNoEvidence(r.getDroppedNoEvidence())
                .droppedLowConfidence(r.getDroppedLowConfidence())
                .droppedBySupersedeWithinBatch(r.getDroppedBySupersedeWithinBatch())
                .duplicatesMerged(r.getDuplicatesMerged())
                .confidencePenalised(r.getConfidencePenalised())
                .hardCapTriggered(r.isHardCapTriggered())
                .evidenceCoverage(r.getEvidenceCoverage())
                .lowCoverage(r.isLowCoverage())
                .strengthOverrides(r.getStrengthOverrides())
                .strengthTagsModified(r.getStrengthTagsModified())
                .promoted(r.getPromoted())
                .inboxOffered(r.getInboxOffered())
                .skipped(r.getSkipped())
                .refreshed(r.getRefreshed())
                .affectsResolved(r.getAffectsResolved())
                .affectsDeferred(r.getAffectsDeferred())
                .persistedMemoryIds(new ArrayList<>(r.getPersistedMemoryIds()))
                .model(r.getModel())
                .durationMs(r.getDurationMs())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private static MarvinNodeInsightsDto toDto(MarvinNodeDocument n) {
        return MarvinNodeInsightsDto.builder()
                .id(n.getId())
                .parentId(n.getParentId())
                .position(n.getPosition())
                .goal(n.getGoal())
                .taskKind(n.getTaskKind() == null ? "" : n.getTaskKind().name())
                .status(n.getStatus() == null ? "" : n.getStatus().name())
                .taskSpec(new LinkedHashMap<>(n.getTaskSpec()))
                .artifacts(new LinkedHashMap<>(n.getArtifacts()))
                .failureReason(n.getFailureReason())
                .spawnedProcessId(n.getSpawnedProcessId())
                .inboxItemId(n.getInboxItemId())
                .createdAt(n.getCreatedAt())
                .startedAt(n.getStartedAt())
                .completedAt(n.getCompletedAt())
                .build();
    }

    /**
     * Maps {@link LlmTraceDocument} to its wire-DTO. The {@code direction}
     * enum is rendered as a lower-case string so the UI can switch on
     * it without importing the server-side enum.
     */
    private static LlmTraceDto toDto(LlmTraceDocument t) {
        return LlmTraceDto.builder()
                .id(t.getId())
                .tenantId(t.getTenantId() == null ? "" : t.getTenantId())
                .projectId(t.getProjectId())
                .sessionId(t.getSessionId())
                .processId(t.getProcessId() == null ? "" : t.getProcessId())
                .engine(t.getEngine())
                .turnId(t.getTurnId())
                .sequence(t.getSequence())
                .direction(t.getDirection() == null
                        ? ""
                        : t.getDirection().name().toLowerCase())
                .role(t.getRole())
                .content(t.getContent())
                .toolName(t.getToolName())
                .toolCallId(t.getToolCallId())
                .modelAlias(t.getModelAlias())
                .providerModel(t.getProviderModel())
                .tokensIn(t.getTokensIn())
                .tokensOut(t.getTokensOut())
                .cacheCreationInputTokens(t.getCacheCreationInputTokens())
                .cacheReadInputTokens(t.getCacheReadInputTokens())
                .elapsedMs(t.getElapsedMs())
                .createdAt(t.getCreatedAt())
                .build();
    }

    // ─── Project-level effective config (Recipes / Tools) ──────────────────

    /**
     * Effective recipe list for a project. Cascade-resolved
     * ({@code project → _vance → bundled}); each entry carries its
     * source so the UI can render the origin badge.
     */
    @GetMapping("/projects/{project}/insights/recipes")
    public List<EffectiveRecipeDto> listEffectiveRecipes(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        List<ResolvedRecipe> resolved = recipeLoader.listAll(tenant, project);
        List<EffectiveRecipeDto> out = new ArrayList<>(resolved.size());
        for (ResolvedRecipe r : resolved) {
            out.add(EffectiveRecipeDto.builder()
                    .name(r.name())
                    .description(r.description())
                    .engine(r.engine())
                    .source(r.source().name())
                    .paramsCount(r.params() == null ? 0 : r.params().size())
                    .hasPromptPrefix(r.promptPrefix() != null && !r.promptPrefix().isBlank())
                    .allowedToolsAdd(r.allowedToolsAdd())
                    .allowedToolsRemove(r.allowedToolsRemove())
                    .defaultActiveSkills(r.defaultActiveSkills())
                    .allowedSkills(r.allowedSkills())
                    .locked(r.locked())
                    .tags(r.tags())
                    .profileKeys(r.profiles() == null ? List.of() : new ArrayList<>(r.profiles().keySet()))
                    .build());
        }
        out.sort(Comparator.comparing(EffectiveRecipeDto::getName));
        return out;
    }

    /**
     * Effective tool list for a project. Walks the same cascade the
     * runtime dispatcher walks ({@code BUILTIN → _vance → project})
     * and attributes each surviving entry to its innermost layer.
     * Disabled inner-layer documents trigger {@code disabledByInnerLayer=true}
     * on the otherwise hidden entry instead of dropping it silently —
     * this is the diagnostic value the insights tab exists for.
     */
    /**
     * Search/research provider instances assembled for the project,
     * with the current availability verdict, a free-text status line
     * from the protocol (Serper credits, Wikipedia "no quota meter"
     * etc.) and the pod-local invocation counter. Read-only —
     * everything writeable lives in {@code research.endpoint.*}
     * settings under the standard cascade editor.
     */
    @GetMapping("/projects/{project}/insights/zarniwoop")
    public List<ZarniwoopInsightsDto> listZarniwoopInstances(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        return zarniwoopInsightsService.listInstances(tenant, project);
    }

    @GetMapping("/projects/{project}/insights/tools")
    public List<EffectiveToolDto> listEffectiveTools(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);

        Map<String, EffectiveToolDto.EffectiveToolDtoBuilder> acc = new LinkedHashMap<>();

        // Layer 1 — built-in beans
        for (Tool t : builtInToolSource.list()) {
            acc.put(t.name(), EffectiveToolDto.builder()
                    .name(t.name())
                    .description(t.description())
                    .primary(t.primary())
                    .deferred(t.deferred())
                    .searchHint(t.searchHint())
                    .source("BUILTIN")
                    .labels(new ArrayList<>(t.labels()))
                    .type(null)
                    .disabledByInnerLayer(false));
        }

        // Layer 2 — tenant-wide _vance project
        applyToolLayer(acc, tenant, HomeBootstrapService.TENANT_PROJECT_NAME, "VANCE");

        // Layer 3 — caller's project (skip if it IS _vance)
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(project)) {
            applyToolLayer(acc, tenant, project, "PROJECT");
        }

        List<EffectiveToolDto> out = new ArrayList<>(acc.size());
        for (EffectiveToolDto.EffectiveToolDtoBuilder b : acc.values()) {
            out.add(b.build());
        }
        out.sort(Comparator.comparing(EffectiveToolDto::getName));
        return out;
    }

    private void applyToolLayer(
            Map<String, EffectiveToolDto.EffectiveToolDtoBuilder> acc,
            String tenant,
            String project,
            String sourceLabel) {
        for (ServerToolConfig cfg : serverToolLoader.loadInProject(tenant, project)) {
            String name = cfg.name();
            if (!cfg.enabled()) {
                // Disabled stop-card — surface as a diagnostic on the existing entry,
                // or insert a placeholder when nothing was there before.
                EffectiveToolDto.EffectiveToolDtoBuilder existing = acc.get(name);
                if (existing != null) {
                    existing.disabledByInnerLayer(true);
                } else {
                    acc.put(name, EffectiveToolDto.builder()
                            .name(name)
                            .description(cfg.description())
                            .primary(false)
                            .deferred(false)
                            .searchHint("")
                            .source(sourceLabel)
                            .labels(new ArrayList<>(cfg.labels()))
                            .type(cfg.type())
                            .disabledByInnerLayer(true));
                }
                continue;
            }
            // Enabled config → fully replaces lower layer
            Tool materialized = serverToolService.lookup(tenant, project, name).orElse(null);
            acc.put(name, EffectiveToolDto.builder()
                    .name(name)
                    .description(materialized != null ? materialized.description() : cfg.description())
                    .primary(materialized != null && materialized.primary())
                    .deferred(materialized != null && materialized.deferred())
                    .searchHint(materialized != null ? materialized.searchHint() : "")
                    .source(sourceLabel)
                    .labels(new ArrayList<>(cfg.labels()))
                    .type(cfg.type())
                    .disabledByInnerLayer(false));
        }
    }

    // ─── Live client-tools per session ─────────────────────────────────────

    /**
     * Tools the client pushed at WebSocket connect-time. The registry
     * lives on the pod that owns the session's bind — same pod as the
     * project's owner, so we forward via {@link PodForwarder} to the
     * project pod's internal endpoint. Diagnostic-only; the registry
     * is rebuilt on every reconnect, so a refresh shows the live
     * picture.
     */
    @GetMapping("/sessions/{sessionId}/insights/client-tools")
    public SessionClientToolsDto listClientTools(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        authority.enforce(httpRequest,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()), Action.ADMIN);

        // Bypass mode (used in single-pod tests): read the local registry
        // directly. In normal operation we always forward, so dev and prod
        // exercise the same path.
        if (workspaceAccessProperties.isBypassProxy()) {
            return clientToolRegistry.entry(sessionId)
                    .map(e -> SessionClientToolsDto.builder()
                            .sessionId(sessionId)
                            .bound(true)
                            .connectionId(e.connectionId())
                            .tools(List.copyOf(e.tools().values()))
                            .build())
                    .orElseGet(() -> SessionClientToolsDto.builder()
                            .sessionId(sessionId)
                            .bound(false)
                            .tools(List.of())
                            .build());
        }

        ProjectPodKey key = new ProjectPodKey(tenant, session.getProjectId());
        String path = "/internal/insights/sessions/"
                + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8)
                + "/client-tools";
        return podForwarder.getJson(key, path, SessionClientToolsDto.class);
    }

    // ─── Cluster pods ──────────────────────────────────────────────────────

    /**
     * Lists every brain-pod row in this brain's cluster. {@code activeProjects}
     * is filtered to the requesting tenant — other tenants' projects on the
     * same pod are dropped server-side and never reach the wire.
     *
     * <p>Staleness is computed against this brain's view of the clock; a pod
     * whose last heartbeat is older than the cluster's stale window comes
     * back with {@code stale=true} regardless of its self-reported status.
     */
    @GetMapping("/cluster/pods")
    public List<BrainPodInsightsDto> listClusterPods(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);

        Instant now = Instant.now();
        String selfPodId = clusterService.selfPodId();
        String tenantPrefix = tenant + "/";

        return clusterService.listCluster().stream()
                .sorted(Comparator.comparing(BrainPodDocument::getNodeName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(doc -> toClusterPodDto(
                        doc, tenantPrefix, selfPodId, clusterService.isStale(doc, now)))
                .toList();
    }

    /**
     * Pure mapping: pod row + tenant prefix → DTO with cross-tenant
     * projects filtered out and the prefix stripped. Static + package-
     * private so it can be unit-tested without the controller stack.
     */
    static BrainPodInsightsDto toClusterPodDto(
            BrainPodDocument doc, String tenantPrefix, String selfPodId, boolean stale) {
        List<String> tenantProjects = doc.getActiveProjects() == null
                ? List.of()
                : doc.getActiveProjects().stream()
                        .filter(p -> p != null && p.startsWith(tenantPrefix))
                        .map(p -> p.substring(tenantPrefix.length()))
                        .sorted()
                        .toList();
        return BrainPodInsightsDto.builder()
                .nodeName(doc.getNodeName())
                .podId(doc.getPodId())
                .clusterId(doc.getClusterId())
                .endpoint(doc.getEndpoint())
                .status(doc.getStatus() != null ? doc.getStatus().name() : "UNKNOWN")
                .stale(stale)
                .selfPod(selfPodId.equals(doc.getPodId()))
                .bootedAt(doc.getBootedAt())
                .lastHeartbeatAt(doc.getLastHeartbeatAt())
                .version(doc.getVersion())
                .tenantProjects(tenantProjects)
                .build();
    }

    // ─── Addons ────────────────────────────────────────────────────────────

    /**
     * All addon rows in the system — enabled and disabled — combined
     * with the on-disk reality (manifest version, .ready marker,
     * checksum verification). Read-only; admin-gated via tenant ADMIN
     * permission like every other admin endpoint here. Addon state is
     * system-wide but we still scope auth per tenant for consistency.
     */
    @GetMapping("/addons")
    public List<AddonInsightDto> listAddons(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        return addonInsightsService.listForInsights();
    }
}
