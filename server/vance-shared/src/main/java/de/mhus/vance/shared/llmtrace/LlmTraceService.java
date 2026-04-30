package de.mhus.vance.shared.llmtrace;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Datenhoheit for the {@code llm_traces} collection. Writers always go
 * through this service — never persist {@link LlmTraceDocument}
 * instances directly via the repository.
 *
 * <p>This service is provider-agnostic: it accepts already-built
 * {@link LlmTraceDocument} entries and persists them. Engines /
 * trace-hooks build the document with the right metadata and call
 * {@link #record(LlmTraceDocument)} — the service only adds
 * {@link LlmTraceDocument#getCreatedAt() createdAt} when missing and
 * stamps the row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmTraceService {

    private final LlmTraceRepository repository;

    /**
     * Persist one trace leg. Sets {@code createdAt} when caller left it
     * blank. Logs and swallows persistence errors — trace persistence
     * is best-effort, never fail the LLM call because tracing failed.
     */
    public LlmTraceDocument record(LlmTraceDocument entry) {
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(java.time.Instant.now());
        }
        try {
            return repository.save(entry);
        } catch (RuntimeException e) {
            log.warn("LlmTraceService.record failed (tenantId='{}' processId='{}' direction={}): {}",
                    entry.getTenantId(), entry.getProcessId(), entry.getDirection(), e.toString());
            return entry;
        }
    }

    /**
     * Page through a process's trace history newest-first. Drives the
     * Insights UI list view; one round-trip groups by {@code turnId}
     * which the UI surfaces.
     */
    public Page<LlmTraceDocument> listByProcess(
            String tenantId, String processId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        return repository.findByTenantIdAndProcessId(
                tenantId, processId, PageRequest.of(safePage, safeSize, sort));
    }

    /**
     * Returns the legs of one specific round-trip in their natural
     * order — INPUT messages first, then OUTPUT, then any TOOL_CALL /
     * TOOL_RESULT pairs.
     */
    public List<LlmTraceDocument> listByTurn(String tenantId, String processId, String turnId) {
        return repository.findByTenantIdAndProcessIdAndTurnIdOrderBySequenceAsc(
                tenantId, processId, turnId);
    }

    /** Hard-delete every trace row for a process. Best-effort; TTL handles bulk cleanup. */
    public long forgetProcess(String tenantId, String processId) {
        return repository.deleteByTenantIdAndProcessId(tenantId, processId);
    }
}
