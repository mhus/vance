package de.mhus.vance.shared.hactar;

import de.mhus.vance.api.hactar.HactarProcessDto;
import de.mhus.vance.api.hactar.HactarRunStatus;
import de.mhus.vance.shared.hactar.journal.JournalRecord;
import de.mhus.vance.shared.hactar.journal.ResultRecord;
import de.mhus.vance.shared.hactar.journal.StartRecord;
import de.mhus.vance.shared.hactar.journal.StateEnteredRecord;
import de.mhus.vance.shared.hactar.journal.StatusRecord;
import de.mhus.vance.shared.hactar.journal.VarRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconstructs a workflow run's current state by walking its journal
 * end-to-end. Idempotent, side-effect-free — the journal is the source
 * of truth; everything the projector returns is derived.
 *
 * <p>The materialised-view collection mentioned in plan §16
 * ({@code hactar_processes}, listing-friendly snapshot) wraps this
 * projector: after every {@link de.mhus.vance.shared.hactar.HactarJournalService#append}
 * the corresponding row is rewritten with the latest projection.
 * Inconsistencies always favour the journal.
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
public class HactarStateProjector {

    private final HactarJournalService journalService;
    private final ObjectMapper objectMapper;

    /**
     * Project the full state of {@code workflowRunId}. Returns
     * {@link Optional#empty()} when there is no {@link StartRecord}
     * yet — i.e. the run id is unknown.
     */
    public Optional<HactarProcessDto> project(String workflowRunId) {
        List<HactarJournalEntry> entries = journalService.read(workflowRunId);
        if (entries.isEmpty()) return Optional.empty();

        // Accumulators
        String tenantId = "";
        String projectId = "";
        String workflowName = "";
        @Nullable String workflowVersion = null;
        @Nullable String startedBy = null;
        @Nullable Map<String, Object> params = null;
        Map<String, Object> vars = new LinkedHashMap<>();
        @Nullable String currentState = null;
        HactarRunStatus status = HactarRunStatus.RUNNING;
        @Nullable String latestStateBeforeTerminal = null;
        @Nullable Map<String, Object> result = null;

        Instant createdAt = entries.get(0).getCreatedAt();
        Instant updatedAt = entries.get(entries.size() - 1).getCreatedAt();
        @Nullable Instant terminatedAt = null;

        boolean sawStart = false;

        for (HactarJournalEntry entry : entries) {
            if (entry.getTenantId() != null && !entry.getTenantId().isBlank()) {
                tenantId = entry.getTenantId();
            }
            if (entry.getProjectId() != null && !entry.getProjectId().isBlank()) {
                projectId = entry.getProjectId();
            }

            Optional<JournalRecord> maybeRecord = journalService.toRecord(entry);
            if (maybeRecord.isEmpty()) continue;
            JournalRecord record = maybeRecord.get();

            if (record instanceof StartRecord r) {
                sawStart = true;
                workflowName = r.getWorkflowName();
                workflowVersion = r.getWorkflowVersion();
                startedBy = r.getStartedBy();
                if (r.getParams() != null) {
                    params = Map.copyOf(r.getParams());
                }
            } else if (record instanceof StateEnteredRecord r) {
                currentState = r.getState();
                if (!isTerminalStatus(status)) {
                    latestStateBeforeTerminal = r.getState();
                }
            } else if (record instanceof VarRecord r) {
                vars.put(r.getKey(), unwrap(r.getValue()));
            } else if (record instanceof StatusRecord r) {
                status = r.getStatus();
                if (isTerminalStatus(status)) {
                    terminatedAt = entry.getCreatedAt();
                }
            } else if (record instanceof ResultRecord r) {
                result = unwrapMap(r.getResult());
            }
        }

        if (!sawStart) return Optional.empty();

        // For terminal runs, freeze the last live state as currentState
        // so the UI can render "ended in state X".
        if (isTerminalStatus(status) && latestStateBeforeTerminal != null) {
            currentState = latestStateBeforeTerminal;
        }

        return Optional.of(HactarProcessDto.builder()
                .workflowRunId(workflowRunId)
                .workflowName(workflowName)
                .workflowVersion(workflowVersion)
                .tenantId(tenantId)
                .projectId(projectId)
                .status(status)
                .currentState(currentState)
                .params(params)
                .vars(vars.isEmpty() ? null : Map.copyOf(vars))
                .startedBy(startedBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .terminatedAt(terminatedAt)
                .result(result)
                .build());
    }

    /**
     * Lightweight status-only projection — analog Nimbus'
     * {@code WorkflowContext.getStatus()}. Returns
     * {@link HactarRunStatus#RUNNING} when no {@link StatusRecord} has
     * been written yet (start of a run before the engine writes its
     * first explicit transition).
     */
    public HactarRunStatus projectStatus(String workflowRunId) {
        return journalService.readLast(workflowRunId, StatusRecord.class)
                .map(StatusRecord::getStatus)
                .orElse(HactarRunStatus.RUNNING);
    }

    /**
     * Current variable map by replaying every {@link VarRecord} in
     * order; later writes win.
     */
    public Map<String, Object> projectVars(String workflowRunId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VarRecord record : journalService.readAll(workflowRunId, VarRecord.class)) {
            out.put(record.getKey(), unwrap(record.getValue()));
        }
        return out;
    }

    private static boolean isTerminalStatus(HactarRunStatus status) {
        return status == HactarRunStatus.DONE
                || status == HactarRunStatus.FAILED
                || status == HactarRunStatus.TERMINATED;
    }

    private @Nullable Object unwrap(@Nullable JsonNode node) {
        if (node == null || node.isNull()) return null;
        return objectMapper.convertValue(node, Object.class);
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Object> unwrapMap(@Nullable JsonNode node) {
        Object raw = unwrap(node);
        if (raw instanceof Map<?, ?> m) {
            return Map.copyOf((Map<String, Object>) m);
        }
        return null;
    }
}
