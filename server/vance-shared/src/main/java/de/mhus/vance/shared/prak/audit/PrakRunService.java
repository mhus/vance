package de.mhus.vance.shared.prak.audit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Persistence service for {@link PrakRunRecord}. Holds the
 * "Datenhoheit" over the {@code prak_runs} collection — every other
 * service writes audit records through this service rather than
 * touching the repository directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakRunService {

    /** Hard cap on returned records to keep dashboard queries bounded. */
    public static final int MAX_LIST_LIMIT = 500;

    private final PrakRunRepository repository;

    /**
     * Persists {@code record}. {@code createdAt} is filled by Spring's
     * {@code @CreatedDate} on insert; callers don't set it.
     */
    public PrakRunRecord save(PrakRunRecord record) {
        PrakRunRecord saved = repository.save(record);
        log.debug("PrakRun saved tenant='{}' project='{}' runId='{}' trigger='{}' promoted={} duration={}ms",
                saved.getTenantId(), saved.getProjectId(),
                saved.getRunId(), saved.getTrigger(),
                saved.getPromoted(), saved.getDurationMs());
        return saved;
    }

    /**
     * Lists the most recent runs for a tenant+project, newest first.
     * {@code limit} is clamped to {@link #MAX_LIST_LIMIT}.
     */
    public List<PrakRunRecord> listRecent(String tenantId, String projectId, int limit) {
        int effective = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return repository.findByTenantIdAndProjectIdOrderByCreatedAtDesc(
                tenantId, projectId, PageRequest.of(0, effective));
    }

    /**
     * Lists the most recent runs for a specific think-process, newest
     * first. {@code limit} is clamped to {@link #MAX_LIST_LIMIT}.
     */
    public List<PrakRunRecord> listByProcess(String tenantId, String processId, int limit) {
        int effective = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return repository.findByTenantIdAndProcessIdOrderByCreatedAtDesc(
                tenantId, processId, PageRequest.of(0, effective));
    }

    /** Lookup by correlation runId — typically 0 or 1 records. */
    public List<PrakRunRecord> findByRunId(String runId) {
        return repository.findByRunId(runId);
    }
}
