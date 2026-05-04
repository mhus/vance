package de.mhus.vance.brain.documents;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Per-process write-behind buffer for {@link DocumentDocument}
 * mutations from kind-aware LLM tools. Each tool call reads through
 * the buffer and writes back through it — so tool-side code stays
 * stateless and atomic-feeling, while the buffer coalesces the
 * actual MongoDB writes.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li><b>Read</b> via {@link #read(String, String)}: cache hit
 *       returns the in-flight body; cache miss loads via
 *       {@link DocumentService#findById(String)}, caches it, returns
 *       the loaded document.</li>
 *   <li><b>Write</b> via {@link #writeBody(String, String, String)}:
 *       updates the cached body, marks the entry dirty, and pushes
 *       the flush deadline {@code +idleFlushSeconds}.</li>
 *   <li><b>Periodic flush</b>: {@link #flushDueEntries()} runs every
 *       few seconds and writes back any dirty entry whose deadline
 *       has passed.</li>
 *   <li><b>Process-end flush</b>: {@link #onStatusChange} catches
 *       {@link ThinkProcessStatus#CLOSED} and flushes everything the
 *       process touched, regardless of deadline.</li>
 * </ol>
 *
 * <h2>Scope</h2>
 * Cache scope is <b>per ({@code processId}, {@code documentId})</b>.
 * Two processes editing the same document don't share state — each
 * has its own draft, last flush wins (same trade-off as if there
 * were no cache at all). Tool calls outside a think-process
 * ({@code processId == null}) bypass the cache and write through
 * directly.
 *
 * <h2>Crash window</h2>
 * On JVM crash, the in-memory drafts are lost — up to
 * {@code idleFlushSeconds} of mutations may be lost. Acceptable for
 * LLM scratch work; for safety-critical operations, callers should
 * call {@link #flush(String, String)} after each step.
 */
@Service
@Slf4j
public class DocumentBufferService {

    private final DocumentService documentService;
    private final long idleFlushMillis;
    private final ConcurrentHashMap<BufferKey, BufferedEntry> entries = new ConcurrentHashMap<>();

    public DocumentBufferService(
            DocumentService documentService,
            @Value("${vance.document.buffer.idle-flush-seconds:60}") long idleFlushSeconds) {
        this.documentService = documentService;
        this.idleFlushMillis = Math.max(1, idleFlushSeconds) * 1000L;
        log.info("DocumentBufferService initialised, idleFlushSeconds={}", idleFlushSeconds);
    }

    /**
     * Read a document through the buffer. Returns the cached body if
     * the document is in the buffer (so reads see the writes the
     * same process already made), otherwise loads fresh from
     * MongoDB and caches the entry without marking it dirty.
     *
     * <p>{@code processId == null} bypasses the cache entirely —
     * direct {@link DocumentService#findById} call, no caching.
     *
     * @return the document, or {@code null} if not found.
     */
    public @Nullable DocumentDocument read(@Nullable String processId, String documentId) {
        Objects.requireNonNull(documentId, "documentId");
        if (processId == null) {
            return documentService.findById(documentId).orElse(null);
        }
        BufferKey key = new BufferKey(processId, documentId);
        BufferedEntry entry = entries.get(key);
        if (entry != null) {
            return projectionOf(entry);
        }
        DocumentDocument fresh = documentService.findById(documentId).orElse(null);
        if (fresh == null) return null;
        entries.put(key, new BufferedEntry(
                fresh, fresh.getInlineText(), false, 0L));
        return fresh;
    }

    /**
     * Write a new body for the document. The cache entry is marked
     * dirty and its flush deadline is pushed by
     * {@link #idleFlushMillis} from now. The actual MongoDB write
     * happens later (periodic flush, process-end flush, or
     * {@link #flush}).
     *
     * <p>{@code processId == null} writes through immediately to
     * MongoDB — no caching.
     */
    public void writeBody(@Nullable String processId, String documentId, String newBody) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(newBody, "newBody");
        if (processId == null) {
            documentService.update(documentId, null, null, newBody, null);
            return;
        }
        BufferKey key = new BufferKey(processId, documentId);
        entries.compute(key, (k, existing) -> {
            BufferedEntry base = existing != null
                    ? existing
                    : loadIntoBuffer(documentId);
            if (base == null) {
                throw new IllegalStateException(
                        "Document not found for buffer write: " + documentId);
            }
            return new BufferedEntry(
                    base.original(),
                    newBody,
                    true,
                    System.currentTimeMillis() + idleFlushMillis);
        });
    }

    /** Force-flush a single document's buffered changes (if dirty),
     *  bypassing the time deadline. No-op when the entry doesn't
     *  exist or is clean. */
    public void flush(@Nullable String processId, String documentId) {
        if (processId == null) return;
        BufferKey key = new BufferKey(processId, documentId);
        BufferedEntry entry = entries.get(key);
        if (entry == null || !entry.dirty()) return;
        flushEntry(key, entry);
    }

    /** Flush every dirty entry for the given process and drop the
     *  cached entries. Called from the process-end listener and
     *  manually by tools that want to abandon the buffer. */
    public void flushAll(@Nullable String processId) {
        if (processId == null) return;
        List<Map.Entry<BufferKey, BufferedEntry>> processEntries = new ArrayList<>();
        for (Map.Entry<BufferKey, BufferedEntry> e : entries.entrySet()) {
            if (e.getKey().processId().equals(processId)) {
                processEntries.add(e);
            }
        }
        for (Map.Entry<BufferKey, BufferedEntry> e : processEntries) {
            try {
                if (e.getValue().dirty()) flushEntry(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn("flushAll failed for {}: {}", e.getKey(), ex.toString());
            }
            entries.remove(e.getKey());
        }
    }

    /**
     * Periodic sweep — flush every dirty entry whose deadline has
     * passed. The sweep itself isn't transactional; an entry that
     * gets re-touched between deadline-check and write will be
     * re-flushed on the next sweep with its new content.
     */
    @Scheduled(fixedDelayString = "#{${vance.document.buffer.sweep-interval-ms:5000}}")
    public void flushDueEntries() {
        long now = System.currentTimeMillis();
        List<Map.Entry<BufferKey, BufferedEntry>> due = new ArrayList<>();
        for (Map.Entry<BufferKey, BufferedEntry> e : entries.entrySet()) {
            BufferedEntry v = e.getValue();
            if (v.dirty() && v.flushDeadlineMillis() <= now) {
                due.add(e);
            }
        }
        for (Map.Entry<BufferKey, BufferedEntry> e : due) {
            try {
                flushEntry(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn("Scheduled flush failed for {}: {}", e.getKey(), ex.toString());
            }
        }
    }

    /**
     * Process-end hook — when a think-process closes, flush
     * everything it touched. Same listener pattern as
     * {@code WorkspaceCreatorCleanupListener}.
     */
    @EventListener
    public void onStatusChange(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) return;
        flushAll(event.processId());
    }

    // ── Internal ────────────────────────────────────────────────────

    private @Nullable BufferedEntry loadIntoBuffer(String documentId) {
        DocumentDocument fresh = documentService.findById(documentId).orElse(null);
        if (fresh == null) return null;
        return new BufferedEntry(fresh, fresh.getInlineText(), false, 0L);
    }

    private void flushEntry(BufferKey key, BufferedEntry entry) {
        try {
            documentService.update(key.documentId(), null, null, entry.currentBody(), null);
            // Mark clean by replacing in the map; concurrent writers
            // who dirty between our compute and the put will get
            // re-flushed on the next sweep.
            entries.compute(key, (k, latest) -> {
                if (latest == null) return null;
                if (latest.flushDeadlineMillis() > entry.flushDeadlineMillis()) {
                    // A newer write sneaked in — keep it dirty.
                    return latest;
                }
                return new BufferedEntry(latest.original(), latest.currentBody(), false, 0L);
            });
        } catch (RuntimeException e) {
            log.warn("Failed to flush document {}: {}", key.documentId(), e.toString());
            throw e;
        }
    }

    /** A read-time projection: clone the original document, swap in
     *  the cached body so the caller sees the in-flight mutations.
     *  Only the fields tools care about (id, path, kind, mimeType,
     *  inlineText, tags, title) are copied — the buffer is for body
     *  edits, not metadata edits. */
    private DocumentDocument projectionOf(BufferedEntry entry) {
        DocumentDocument original = entry.original();
        DocumentDocument copy = new DocumentDocument();
        copy.setId(original.getId());
        copy.setTenantId(original.getTenantId());
        copy.setProjectId(original.getProjectId());
        copy.setPath(original.getPath());
        copy.setName(original.getName());
        copy.setTitle(original.getTitle());
        copy.setKind(original.getKind());
        copy.setMimeType(original.getMimeType());
        copy.setSize(original.getSize());
        copy.setTags(original.getTags() == null ? null : new ArrayList<>(original.getTags()));
        copy.setHeaders(original.getHeaders() == null ? null : new HashMap<>(original.getHeaders()));
        copy.setInlineText(entry.currentBody());
        copy.setStorageId(original.getStorageId());
        copy.setStatus(original.getStatus());
        copy.setCreatedAt(original.getCreatedAt());
        copy.setCreatedBy(original.getCreatedBy());
        return copy;
    }

    /** Cache key — process scope + document id. */
    private record BufferKey(String processId, String documentId) {}

    /** Buffered entry — original baseline + current (possibly
     *  modified) body + dirty flag + flush deadline. */
    private record BufferedEntry(
            DocumentDocument original,
            String currentBody,
            boolean dirty,
            long flushDeadlineMillis) {}
}
