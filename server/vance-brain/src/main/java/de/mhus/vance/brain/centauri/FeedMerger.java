package de.mhus.vance.brain.centauri;

import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Merges the pages of several streams into one chronological page and
 * computes the cursor that resumes after it.
 *
 * <p>Pure logic, no Spring, no IO — the part of Centauri that can be wrong
 * without anyone noticing, so it is separated to be tested directly.
 *
 * <h2>Why the cursor only advances to what the page reached</h2>
 * If a stream returns twenty entries and three fit on the page, the other
 * seventeen are discarded and re-fetched next time. That trades a redundant
 * fetch for statelessness: no buffer, no session state, no Redis
 * requirement, and no pod affinity.
 *
 * <h2>Why the tie-break matters</h2>
 * Two entries with the same timestamp need one stable order across
 * requests, or the endless scroll duplicates and skips rows at page
 * boundaries. Ordering is {@code (publishedAt, streamKey, itemId)} —
 * timestamps alone are not unique, the other two make it so.
 *
 * <h2>Why a rejected entry still advances the cursor</h2>
 * An entry the post-filter dropped has been decided about just as much as a
 * delivered one. Advancing only past delivered entries would let a filter
 * that rejects everything from one stream re-fetch the same entries
 * forever. So the cursor moves past every entry at or before the cut,
 * delivered or not.
 *
 * <h2>Two cursor mechanisms, each for what it is good at</h2>
 * {@link FeedSourceInstance#cursorAfter(FeedItem)} expresses "resume after
 * this entry", which is the only way to describe a cut in the middle of a
 * fetched page. {@link FeedPage#nextCursor()} expresses "resume after this
 * page", which is the only way to make progress when a page came back empty
 * — and without it an empty page with {@code hasMore} would leave the cursor
 * untouched and the client asking the same question forever.
 */
public final class FeedMerger {

    /**
     * How much to over-fetch per stream when the filter cannot be pushed
     * down. Without it a page of twenty shrinks to three; with too much of
     * it every scroll step costs the source a large page. Three is a
     * starting point, not a measured optimum.
     */
    static final int POST_FILTER_OVER_FETCH = 3;

    private FeedMerger() {
        /* static entry point only */
    }

    /** One stream's answer, as handed to the merge. */
    public record StreamFetch(
            FeedStream stream,
            FeedSourceInstance instance,
            FeedPage page) { }

    /** The merged page plus the cursor that resumes after it. */
    public record MergeResult(
            List<CentauriItem> items,
            CentauriCursor cursor,
            boolean hasMore,
            int droppedByFilter,
            int droppedAsDuplicate) { }

    /**
     * Per-stream fetch limit for a page of {@code pageSize}, bounded by what
     * the source admits it can serve.
     */
    public static int fetchLimit(int pageSize, boolean needsPostFilter, int maxPageSize) {
        int wanted = needsPostFilter ? pageSize * POST_FILTER_OVER_FETCH : pageSize;
        return Math.max(1, Math.min(wanted, maxPageSize));
    }

    public static MergeResult merge(
            List<StreamFetch> fetches,
            FeedFilter filter,
            int pageSize,
            FeedDirection direction,
            CentauriCursor incoming) {

        Comparator<Candidate> order = comparator(direction);

        // Sort each stream's entries ourselves rather than trusting the page to
        // arrive ordered. The contract asks for it, but a source that violates
        // it must not be able to corrupt the cursor — it should only lose
        // ordering quality.
        Map<String, List<Candidate>> byStream = new LinkedHashMap<>();
        List<Candidate> all = new ArrayList<>();
        for (StreamFetch fetch : fetches) {
            List<Candidate> mine = new ArrayList<>(fetch.page().items().size());
            for (FeedItem item : fetch.page().items()) {
                mine.add(new Candidate(fetch, item, filter.matches(item)));
            }
            mine.sort(order);
            byStream.put(fetch.stream().key(), mine);
            all.addAll(mine);
        }
        all.sort(order);

        List<CentauriItem> delivered = new ArrayList<>(Math.min(pageSize, all.size()));
        Set<String> seenUrls = new HashSet<>();
        int droppedByFilter = 0;
        int droppedAsDuplicate = 0;
        @Nullable Candidate cut = null;

        for (Candidate candidate : all) {
            if (delivered.size() >= pageSize) {
                break;
            }
            cut = candidate;
            if (!candidate.accepted()) {
                droppedByFilter++;
                continue;
            }
            // Deduplication is page-local on purpose: the merge buffer holds the
            // information anyway, while across pages it would need state that
            // the stable ordering above makes rarely worth its price.
            if (!seenUrls.add(normalizeUrl(candidate.item().url()))) {
                droppedAsDuplicate++;
                continue;
            }
            delivered.add(new CentauriItem(
                    candidate.item(),
                    candidate.fetch().instance().id(),
                    candidate.fetch().instance().displayName(),
                    candidate.stream().selector()));
        }

        // Nothing delivered although entries were looked at: every one was
        // rejected, so the cut is the last of them. Without this the cursor
        // would stand still and the client would ask for the same page again.
        if (delivered.isEmpty() && !all.isEmpty()) {
            cut = all.get(all.size() - 1);
        }

        CentauriCursor.Builder next = new CentauriCursor.Builder()
                .carryOver(incoming)
                .retainOnly(streamKeys(fetches, incoming));

        boolean hasMore = false;
        for (StreamFetch fetch : fetches) {
            List<Candidate> mine = byStream.getOrDefault(fetch.stream().key(), List.of());
            int passed = countPassedOver(mine, cut, order);
            boolean leftOver = passed < mine.size();

            if (passed == mine.size() && fetch.page().nextCursor() != null) {
                // The whole fetched page was consumed (or was empty): the
                // source's own page-end cursor is both more accurate and the
                // only thing available when there were no entries at all.
                next.advance(fetch.stream(), fetch.page().nextCursor());
            } else if (passed > 0) {
                next.advance(fetch.stream(),
                        fetch.instance().cursorAfter(mine.get(passed - 1).item()));
            }

            if (fetch.page().hasMore() || leftOver) {
                hasMore = true;
            } else {
                next.markExhausted(fetch.stream());
            }
        }
        // Streams already exhausted before this round were not fetched, so the
        // loop above never saw them — carry the flag forward explicitly.
        for (String key : incoming.exhausted()) {
            next.markExhausted(FeedStream.parseKey(key));
        }
        if (!delivered.isEmpty()) {
            next.watermark(delivered.get(delivered.size() - 1).item().publishedAt());
        } else if (cut != null) {
            next.watermark(cut.item().publishedAt());
        }

        return new MergeResult(
                List.copyOf(delivered), next.build(), hasMore,
                droppedByFilter, droppedAsDuplicate);
    }

    // ── internals ────────────────────────────────────────────────────

    /**
     * How many of this stream's entries the page moved past — delivered or
     * deliberately dropped. The list is sorted, so the count doubles as the
     * index of the first entry that was <i>not</i> reached.
     */
    private static int countPassedOver(
            List<Candidate> sorted, @Nullable Candidate cut, Comparator<Candidate> order) {
        if (cut == null) {
            return 0;
        }
        int passed = 0;
        for (Candidate candidate : sorted) {
            if (order.compare(candidate, cut) > 0) {
                break;
            }
            passed++;
        }
        return passed;
    }

    /**
     * Keys worth keeping in the next cursor: the streams just fetched plus
     * the ones already exhausted. Anything else has been removed from the
     * feed configuration and its cursor is dead weight.
     */
    private static Set<String> streamKeys(List<StreamFetch> fetches, CentauriCursor incoming) {
        Set<String> keys = new HashSet<>(incoming.exhausted());
        for (StreamFetch fetch : fetches) {
            keys.add(fetch.stream().key());
        }
        return keys;
    }

    private static Comparator<Candidate> comparator(FeedDirection direction) {
        Comparator<Candidate> byTime = Comparator.comparing(c -> c.item().publishedAt());
        Comparator<Candidate> directed =
                direction == FeedDirection.NEWER ? byTime : byTime.reversed();
        return directed
                .thenComparing(c -> c.stream().key())
                .thenComparing(c -> c.item().id());
    }

    /**
     * Normalise a URL enough to recognise the same story arriving through two
     * streams: host without {@code www}, no trailing slash, no tracking
     * parameters. Deliberately conservative — a wrong merge hides an entry,
     * which is worse than showing it twice.
     */
    static String normalizeUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost() == null
                    ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = stripTracking(uri.getQuery());
            return host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (RuntimeException e) {
            return raw.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static String stripTracking(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String part : query.split("&")) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_") || lower.startsWith("fbclid=")
                    || lower.startsWith("gclid=")) {
                continue;
            }
            kept.add(part);
        }
        return String.join("&", kept);
    }

    /** An entry together with its stream and its filter verdict. */
    private record Candidate(StreamFetch fetch, FeedItem item, boolean accepted) {

        FeedStream stream() {
            return fetch.stream();
        }
    }
}
