package de.mhus.vance.brain.context;

import de.mhus.vance.shared.thinkprocess.ReadStateEntry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-side cache for context-assembly: tracks which resources the
 * process has already pulled into prompt content (LRU) and which
 * one-shot auto-attachments have already been delivered (set).
 * Operates on {@link ThinkProcessDocument} fields via
 * {@link ThinkProcessService} — never touches MongoDB directly so the
 * data-ownership rule from {@code CLAUDE.md} stays intact.
 *
 * <p>The service is intentionally small and pure-functional on the
 * read paths: {@link #hasFresh} and {@link #wasShown} consult the
 * in-memory process snapshot the caller already holds. Write paths
 * go through atomic Mongo updates ({@code $push.$slice} for the LRU,
 * {@code $addToSet} for the one-shot markers) so concurrent lanes
 * don't lose entries.
 *
 * <p>Bounds come from configuration (defaults match Claude Code's
 * {@code fileStateCache.ts:18}):
 * <ul>
 *   <li>{@code vance.context.readState.maxEntries} — default 100</li>
 *   <li>{@code vance.context.shownOnce.maxEntries} — default 500
 *       (only checked as a soft cap on read; the underlying Mongo
 *       update keeps adding regardless — runaway producers must be
 *       fixed at the source, not silently dropped)</li>
 * </ul>
 *
 * <p>See {@code planning/brain-context-assembler.md} §3 + §4. v1 does
 * not implement {@code findChanged(...)} — that pairs with a
 * {@code ResourceProbe} interface and the {@code composeAttachments}
 * call site, both deferred to the next step.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadStateService {

    /** Default LRU cap for read-state entries. */
    public static final int DEFAULT_READ_STATE_MAX_ENTRIES = 100;

    /** Default soft cap for shown-once markers. */
    public static final int DEFAULT_SHOWN_ONCE_MAX_ENTRIES = 500;

    private final ThinkProcessService thinkProcessService;

    @Value("${vance.context.readState.maxEntries:" + DEFAULT_READ_STATE_MAX_ENTRIES + "}")
    private int readStateMaxEntries = DEFAULT_READ_STATE_MAX_ENTRIES;

    @Value("${vance.context.shownOnce.maxEntries:" + DEFAULT_SHOWN_ONCE_MAX_ENTRIES + "}")
    private int shownOnceMaxEntries = DEFAULT_SHOWN_ONCE_MAX_ENTRIES;

    /**
     * Returns {@code true} when {@code key} is present in the process's
     * read-state with a matching {@code contentHash}. A stale hash
     * (resource changed since the cache entry was written) returns
     * {@code false} — the caller should treat this as "must re-read".
     *
     * <p>Pure in-memory lookup on the supplied process snapshot — no
     * Mongo round-trip. The snapshot may be slightly stale; the
     * trade-off matches the plan's "volatile" classification of the
     * field. If absolute freshness is needed the caller re-loads the
     * process before asking.
     */
    public boolean hasFresh(ThinkProcessDocument process, String key, String contentHash) {
        if (process == null || key == null || contentHash == null) return false;
        List<ReadStateEntry> entries = process.getReadState();
        if (entries == null || entries.isEmpty()) return false;
        for (ReadStateEntry e : entries) {
            if (key.equals(e.key()) && contentHash.equals(e.contentHash())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a fresh read of {@code key} with {@code contentHash} into
     * the process's LRU. Idempotent on identical (key, hash) — a
     * second call with the same pair simply pushes a new entry; the
     * older duplicate gets aged out naturally when the bound is hit.
     * Optimising for deduplication on write would require a Mongo
     * read-modify-write and the in-memory cost of duplicates is
     * negligible.
     */
    public void recordRead(
            ThinkProcessDocument process,
            String key,
            String contentHash,
            boolean partialView,
            @Nullable Long bytes) {
        if (process == null || process.getId() == null) return;
        if (key == null || key.isBlank() || contentHash == null || contentHash.isBlank()) {
            return;
        }
        ReadStateEntry entry = new ReadStateEntry(
                key, contentHash, Instant.now(), partialView, bytes);
        thinkProcessService.appendReadStateEntry(
                process.getId(), entry, readStateMaxEntries);
    }

    /**
     * Returns {@code true} when {@code marker} has already been recorded
     * in {@code shownOnce}. In-memory lookup on the process snapshot.
     */
    public boolean wasShown(ThinkProcessDocument process, String marker) {
        if (process == null || marker == null) return false;
        return process.getShownOnce() != null
                && process.getShownOnce().contains(marker);
    }

    /**
     * Atomic try-add: marks {@code marker} as shown if it wasn't
     * already, returning {@code true} on first add. Use case: an
     * auto-attachment that should be injected exactly once per
     * process lifetime calls this guarded by the return value.
     *
     * <p>The {@link #shownOnceMaxEntries} soft cap is a runaway guard;
     * a producer that consistently fills the set should be fixed at
     * the source. When the cap is exceeded we still attempt the add
     * (Mongo doesn't care) but emit a warn-once log so the situation
     * is visible.
     */
    public boolean tryMarkShown(ThinkProcessDocument process, String marker) {
        if (process == null || process.getId() == null) return false;
        if (marker == null || marker.isBlank()) return false;
        if (process.getShownOnce() != null
                && process.getShownOnce().size() >= shownOnceMaxEntries) {
            log.warn("shownOnce cap exceeded for process {} (size={}, cap={}) — "
                            + "check producer for runaway markers",
                    process.getId(), process.getShownOnce().size(), shownOnceMaxEntries);
        }
        return thinkProcessService.tryAddShownOnce(process.getId(), marker);
    }

    /**
     * Resets both {@code readState} and {@code shownOnce} on the
     * process. Called at session-close (caller's responsibility) or on
     * explicit reset.
     */
    public void clear(ThinkProcessDocument process) {
        if (process == null || process.getId() == null) return;
        thinkProcessService.clearVolatileContextState(process.getId());
    }

    /**
     * Convenience: computes a stable SHA-256 hex digest of {@code content}
     * for storage as {@code contentHash}. Returns an empty string for
     * blank input — callers should treat that as "no hash, treat as
     * always-stale". SHA-256 is not security-critical here, just a
     * fast change-detector.
     */
    public static String hashContent(@Nullable String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed in the standard distribution; the
            // catch exists only so we don't have to bubble a checked
            // exception through the entire context-assembly layer.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
