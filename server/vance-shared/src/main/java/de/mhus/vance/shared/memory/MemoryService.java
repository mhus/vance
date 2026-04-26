package de.mhus.vance.shared.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Memory lifecycle and lookup — the single entry point to memory data.
 *
 * <p>Reads are sorted by {@code createdAt} ascending, the natural order
 * a consumer would replay them in. {@code activeBy*} variants exclude
 * superseded entries so callers don't have to filter manually.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryService {

    private static final Sort BY_CREATED = Sort.by(Sort.Direction.ASC, "createdAt");

    private final MemoryRepository repository;

    // ──────────────────── Writes ────────────────────

    /**
     * Persists {@code entry}. {@code createdAt} is filled by the
     * framework on insert; callers don't set it.
     */
    public MemoryDocument save(MemoryDocument entry) {
        MemoryDocument saved = repository.save(entry);
        log.debug("Memory saved tenant='{}' project='{}' session='{}' process='{}' kind={} id='{}'",
                saved.getTenantId(), saved.getProjectId(),
                saved.getSessionId(), saved.getThinkProcessId(),
                saved.getKind(), saved.getId());
        return saved;
    }

    /**
     * Marks {@code oldId} as replaced by {@code newId} (which must
     * already be persisted). Idempotent on already-superseded entries.
     */
    public Optional<MemoryDocument> supersede(String oldId, String newId) {
        return markSuperseded(oldId, newId);
    }

    /**
     * Marks {@code id} as superseded with no replacement — the
     * scratchpad-style "delete" path. The record stays in the
     * database, audit-readable, but the {@code activeBy*} queries
     * stop returning it.
     */
    public Optional<MemoryDocument> markDeleted(String id) {
        return markSuperseded(id, null);
    }

    private Optional<MemoryDocument> markSuperseded(String id, @org.jspecify.annotations.Nullable String newId) {
        Optional<MemoryDocument> opt = repository.findById(id);
        if (opt.isEmpty()) return opt;
        MemoryDocument doc = opt.get();
        if (doc.getSupersededAt() != null) {
            return opt; // already superseded — leave timestamps alone.
        }
        doc.setSupersededByMemoryId(newId);
        doc.setSupersededAt(Instant.now());
        return Optional.of(repository.save(doc));
    }

    // ──────────────────── Reads ────────────────────

    public Optional<MemoryDocument> findById(String id) {
        return repository.findById(id);
    }

    public List<MemoryDocument> listByProcess(String tenantId, String thinkProcessId) {
        return repository.findByTenantIdAndThinkProcessId(
                tenantId, thinkProcessId, BY_CREATED);
    }

    public List<MemoryDocument> listByProcessAndKind(
            String tenantId, String thinkProcessId, MemoryKind kind) {
        return repository.findByTenantIdAndThinkProcessIdAndKind(
                tenantId, thinkProcessId, kind, BY_CREATED);
    }

    /** Process-scoped, only entries that haven't been superseded yet. */
    public List<MemoryDocument> activeByProcessAndKind(
            String tenantId, String thinkProcessId, MemoryKind kind) {
        return repository
                .findByTenantIdAndThinkProcessIdAndKindAndSupersededAtIsNull(
                        tenantId, thinkProcessId, kind, BY_CREATED);
    }

    /**
     * Active entries for a {@code (process, kind, title)} triple — the
     * scratchpad named-slot lookup. Returns the most-recent active
     * entry first; usually there's at most one but the list shape lets
     * callers detect a write-write race if it ever happens.
     */
    public List<MemoryDocument> activeByProcessKindAndTitle(
            String tenantId, String thinkProcessId, MemoryKind kind, String title) {
        return repository
                .findByTenantIdAndThinkProcessIdAndKindAndTitleAndSupersededAtIsNull(
                        tenantId, thinkProcessId, kind, title,
                        Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public List<MemoryDocument> listBySessionAndKind(
            String tenantId, String sessionId, MemoryKind kind) {
        return repository.findByTenantIdAndSessionIdAndKind(
                tenantId, sessionId, kind, BY_CREATED);
    }

    public List<MemoryDocument> listByProjectAndKind(
            String tenantId, String projectId, MemoryKind kind) {
        return repository.findByTenantIdAndProjectIdAndKind(
                tenantId, projectId, kind, BY_CREATED);
    }

    // ──────────────────── Cleanup ────────────────────

    /** Drops every memory entry owned by a process. Used on process delete. */
    public long deleteByProcess(String tenantId, String thinkProcessId) {
        long n = repository.deleteByTenantIdAndThinkProcessId(tenantId, thinkProcessId);
        if (n > 0) {
            log.info("Deleted {} memory entries for process tenant='{}' process='{}'",
                    n, tenantId, thinkProcessId);
        }
        return n;
    }
}
