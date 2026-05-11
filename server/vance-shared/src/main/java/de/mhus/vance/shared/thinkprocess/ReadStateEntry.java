package de.mhus.vance.shared.thinkprocess;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Single entry in {@link ThinkProcessDocument#getReadState()}'s LRU
 * cache of resources the process has already pulled into context.
 * Drives read-dedup: a tool that wants to inject a file/document into
 * the prompt asks the {@code ReadStateService} whether the same
 * {@code key} has been seen with the same {@code contentHash} — and
 * skips the inject if yes.
 *
 * <p>{@code key} uses the typed-resource namespace shared with the
 * history-search marker layer: {@code CLIENT_FILE:/abs/path},
 * {@code WORKSPACE:<process>/<rel>}, {@code DOCUMENT:<id>},
 * {@code MEMORY:<id>}. Keeping both subsystems on the same vocabulary
 * means a tool that reads a doc can match its earlier write marker
 * (and vice versa) without a translation layer.
 *
 * <p>{@code partialView} mirrors Claude Code's {@code isPartialView}
 * flag: when an auto-inject delivered only a partial view of the
 * source (stripped HTML comments, frontmatter, truncated content),
 * a follow-up {@code Edit}/{@code Write} should still require an
 * explicit {@code Read} first. See
 * {@code /Volumes/EXI/labor/claude-code/src/utils/fileStateCache.ts:14}.
 *
 * <p>Volatile by design — see the {@code readState} field doc on
 * {@link ThinkProcessDocument}.
 */
public record ReadStateEntry(
        String key,
        String contentHash,
        Instant fetchedAt,
        boolean partialView,
        @Nullable Long bytesAtFetch) {

    public ReadStateEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash is required");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt is required");
        }
    }
}
