package de.mhus.vance.toolpack.jaglan;

import java.time.Duration;

import de.mhus.vance.api.documents.MountAccess;
import org.jspecify.annotations.Nullable;

/**
 * What a mount source declares about itself. Fetched once per instance and
 * held in a cache; the declaration is what lets a folder listing show a
 * mount without touching it.
 *
 * @param access      read-only or read-write. The source decides — Vance
 *                    never assumes write access it was not offered.
 * @param canSearch   the source can answer a search itself. Brain-side RAG
 *                    does not apply to mounted content, but a library
 *                    <em>can</em> search its own catalogue, and delegating
 *                    beats listing it blind.
 * @param itemCount   how much the source holds, if it knows. Used as the
 *                    folder count for {@code _ext/<mount>} when no fresh
 *                    listing exists. {@code null} means unknown, which is
 *                    not the same as 0 — 0 reads as "empty folder".
 * @param metadataTtl how long directory and metadata answers may be cached.
 *                    {@link Duration#ZERO} means <b>do not cache</b>.
 * @param maxBytes    largest content the source will serve, {@code null} for
 *                    no stated limit.
 * @param supportsQuery the source serves <b>parameterised reads</b>: the same
 *                    path with a query string is a computed view of it, and
 *                    the source is prepared to receive that query. Default
 *                    {@code false} — a plain file store has no parameters, and
 *                    a query it never asked for must be refused rather than
 *                    dropped, or the reader silently gets the unparameterised
 *                    document while believing they asked for a slice of it.
 * @param displayName label for configuration UI and logs.
 */
public record JaglanCapabilities(
        MountAccess access,
        boolean canSearch,
        @Nullable Long itemCount,
        Duration metadataTtl,
        @Nullable Long maxBytes,
        boolean supportsQuery,
        @Nullable String displayName) {

    /** Applied when a source states no TTL at all. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * Floor for a source that asks for no caching.
     *
     * <p>A mount that forbids metadata persistence outright cannot be served
     * by this design at all — the metadata shell row <em>is</em> a cache, and
     * honouring "never" would mean a second architecture (virtual documents)
     * selected by a number coming from a foreign source. So "no caching"
     * becomes "the shortest cache we can still work with", and a source that
     * genuinely cannot accept that is refused when it is configured, rather
     * than half-working.
     */
    public static final Duration MIN_TTL = Duration.ofSeconds(10);

    public JaglanCapabilities {
        if (access == null) access = MountAccess.UNKNOWN;
        if (itemCount != null && itemCount < 0) itemCount = null;
        if (maxBytes != null && maxBytes <= 0) maxBytes = null;
        // Note the asymmetry with FeedCapabilities, which folds zero into
        // its default: here zero is a statement ("do not cache"), so it is
        // clamped to the floor instead of being replaced by five minutes.
        // Folding it would mean a source saying "never" gets cached for the
        // default interval — a mistake that never looks like one.
        if (metadataTtl == null || metadataTtl.isNegative()) {
            metadataTtl = DEFAULT_TTL;
        } else if (metadataTtl.compareTo(MIN_TTL) < 0) {
            metadataTtl = MIN_TTL;
        }
    }

    /** Read-write, no search, unknown size, default TTL, no query support. */
    public static JaglanCapabilities readWrite() {
        return new JaglanCapabilities(
                MountAccess.RW, false, null, DEFAULT_TTL, null, false, null);
    }

    /** Read-only, no search, unknown size, default TTL, no query support. */
    public static JaglanCapabilities readOnly() {
        return new JaglanCapabilities(
                MountAccess.RO, false, null, DEFAULT_TTL, null, false, null);
    }
}
