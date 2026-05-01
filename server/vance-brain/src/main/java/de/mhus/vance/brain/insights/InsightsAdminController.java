package de.mhus.vance.brain.insights;

import de.mhus.vance.api.insights.ActiveSkillInsightsDto;
import de.mhus.vance.api.insights.ChatMessageInsightsDto;
import de.mhus.vance.api.insights.MarvinNodeInsightsDto;
import de.mhus.vance.api.insights.MemoryInsightsDto;
import de.mhus.vance.api.insights.PendingMessageInsightsDto;
import de.mhus.vance.api.insights.SessionInsightsDto;
import de.mhus.vance.api.insights.ThinkProcessInsightsDto;
import de.mhus.vance.api.llmtrace.LlmTraceDto;
import de.mhus.vance.api.llmtrace.LlmTraceListResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final RequestAuthority authority;

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
                .map(InsightsAdminController::toDto)
                .toList();
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

    private static ThinkProcessInsightsDto toDto(ThinkProcessDocument p) {
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
                .pendingMessages(p.getPendingMessages().stream()
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

    private static PendingMessageInsightsDto toDto(PendingMessageDocument m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotNull(payload, "content", m.getContent());
        putIfNotNull(payload, "idempotencyKey", m.getIdempotencyKey());
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
                .at(m.getAt())
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
                .elapsedMs(t.getElapsedMs())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
