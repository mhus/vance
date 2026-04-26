package de.mhus.vance.shared.memory;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Named-slot façade over {@link MemoryService} for
 * {@link MemoryKind#SCRATCHPAD} entries scoped to a single
 * think-process.
 *
 * <p>Slot semantics: a {@code title} is the slot name; writing the
 * same title again creates a fresh entry and supersedes the previous
 * one — the audit chain stays intact, callers always see the latest
 * value through {@link #get}. {@link #delete} marks the active entry
 * superseded with no replacement so it disappears from {@link #list}
 * but the row persists for inspection.
 *
 * <p>v1 is process-scoped only. Session- or project-wide scratchpads
 * are a small extension when an engine actually wants them — add
 * additional methods rather than overloading the existing ones to
 * keep the slot semantics explicit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScratchpadService {

    private final MemoryService memoryService;

    /**
     * Sets a slot's content. The previous active entry with the same
     * title (if any) is superseded by the new one.
     */
    public MemoryDocument set(
            String tenantId, String projectId, String sessionId,
            String thinkProcessId, String title, String content) {
        requireSlot(title);
        Optional<MemoryDocument> previous = active(tenantId, thinkProcessId, title);
        MemoryDocument fresh = MemoryDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId == null ? "" : projectId)
                .sessionId(sessionId)
                .thinkProcessId(thinkProcessId)
                .kind(MemoryKind.SCRATCHPAD)
                .title(title)
                .content(content == null ? "" : content)
                .build();
        MemoryDocument saved = memoryService.save(fresh);
        if (previous.isPresent() && previous.get().getId() != null && saved.getId() != null) {
            memoryService.supersede(previous.get().getId(), saved.getId());
        }
        log.debug("Scratchpad set tenant='{}' process='{}' title='{}' chars={}",
                tenantId, thinkProcessId, title, saved.getContent().length());
        return saved;
    }

    /** Active entry for a slot, or empty if none. */
    public Optional<MemoryDocument> get(
            String tenantId, String thinkProcessId, String title) {
        requireSlot(title);
        return active(tenantId, thinkProcessId, title);
    }

    /** All active scratchpad entries for the process. */
    public List<MemoryDocument> list(String tenantId, String thinkProcessId) {
        return memoryService.activeByProcessAndKind(
                tenantId, thinkProcessId, MemoryKind.SCRATCHPAD);
    }

    /**
     * Marks the active entry of a slot superseded with no replacement
     * (audit-friendly tombstone). Returns {@code true} if a slot was
     * actually deleted.
     */
    public boolean delete(String tenantId, String thinkProcessId, String title) {
        requireSlot(title);
        Optional<MemoryDocument> previous = active(tenantId, thinkProcessId, title);
        if (previous.isEmpty() || previous.get().getId() == null) {
            return false;
        }
        memoryService.markDeleted(previous.get().getId());
        log.debug("Scratchpad delete tenant='{}' process='{}' title='{}'",
                tenantId, thinkProcessId, title);
        return true;
    }

    private Optional<MemoryDocument> active(
            String tenantId, String thinkProcessId, String title) {
        List<MemoryDocument> hits = memoryService.activeByProcessKindAndTitle(
                tenantId, thinkProcessId, MemoryKind.SCRATCHPAD, title);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    private static void requireSlot(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Scratchpad slot title is required");
        }
    }
}
