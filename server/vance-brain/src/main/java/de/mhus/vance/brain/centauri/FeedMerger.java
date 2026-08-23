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
import lombok.extern.slf4j.Slf4j;
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
 *
 * <h2>Why a stream that cannot advance is retired</h2>
 * A source may hand back an empty page, no {@code nextCursor} and
 * {@code hasMore = true}. None of that is representable as progress: the next
 * request would be identical to this one, so the scroll would spin. The claim
 * is therefore not believed — the stream is marked exhausted and the reason
 * logged. One stream drops out of this scroll; believing it costs the whole
 * view.
 *
 * <h2>Why silence is an input rather than an absence</h2>
 * A stream that did not answer this round looks, from a list of fetches
 * alone, exactly like a stream that is no longer configured — and the two
 * demand opposite handling. A removed stream's cursor is dead weight; a
 * timed-out stream's cursor is the reader's scroll position, and dropping it
 * restarts that source at its newest entry on the next page. So the merge is
 * told about the streams that stayed silent, and about which kind of silence
 * it was — see {@link StreamSilence}.
 */
@Slf4j
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

    /**
     * One stream's answer, as handed to the merge.
     *
     * @param pushdown exactly the filter subset this source was given, so the
     *                 post-filter can skip what the source already answered.
     *                 See {@link FeedFilter#matches(FeedItem, FeedFilter)} —
     *                 re-checking a text match against text the source does not
     *                 deliver drops hits the source found correctly.
     */
    public record StreamFetch(
            FeedStream stream,
            FeedSourceInstance instance,
            FeedPage page,
            FeedFilter pushdown) {

        public StreamFetch {
            pushdown = pushdown == null ? FeedFilter.none() : pushdown;
        }
    }

    /**
     * A requested stream that handed back no page this round.
     *
     * <p>What the merge needs is not the exact reason but whether the silence
     * is <b>a statement about the stream</b> or <b>the absence of one</b>.
     * Everything else follows from that single distinction, which is why it
     * is a two-valued {@link Kind} rather than a copy of
     * {@link CentauriNote.Kind}: the note explains the silence to a human,
     * this classifies it for the cursor.
     */
    public record StreamSilence(FeedStream stream, Kind kind) {

        public enum Kind {

            /**
             * The stream answered by not being asked: it is not configured,
             * it is switched off, or it does not declare the facet the reader
             * selected. A round may end on that — the answer will not change
             * between two page requests.
             */
            SETTLED,

            /**
             * Nobody said anything: a timeout, a transport failure, a
             * cooldown from an earlier one. The stream may well have more
             * entries, so the scroll must not be declared finished because of
             * it, and its cursor must survive untouched — the next round has
             * to resume where the reader is, not at the top of that source.
             */
            UNRESOLVED
        }

        public static StreamSilence settled(FeedStream stream) {
            return new StreamSilence(stream, Kind.SETTLED);
        }

        public static StreamSilence unresolved(FeedStream stream) {
            return new StreamSilence(stream, Kind.UNRESOLVED);
        }

        boolean isUnresolved() {
            return kind == Kind.UNRESOLVED;
        }
    }

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

    /**
     * @param silences the requested streams that produced no page, and why
     *                 that matters — see {@link StreamSilence}. Never derived
     *                 from {@code fetches}: their absence there is exactly
     *                 what cannot be told apart from a removed stream.
     */
    public static MergeResult merge(
            List<StreamFetch> fetches,
            List<StreamSilence> silences,
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
                mine.add(new Candidate(fetch, item, filter.matches(item, fetch.pushdown())));
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
        @Nullable Candidate lastDelivered = null;

        // pageSize >= 1 is a precondition (CentauriPageRequest enforces it), and
        // it is what makes the loop below sufficient on its own: the break only
        // fires once something was delivered, so an empty `delivered` means every
        // candidate was looked at and `cut` is already the last of them. That is
        // what keeps a round in which the filter rejected everything from
        // standing still.
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
            lastDelivered = candidate;
        }

        CentauriCursor.Builder next = new CentauriCursor.Builder()
                .carryOver(incoming)
                .retainOnly(streamKeys(fetches, silences, incoming));

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

            if (leftOver) {
                // Entries already fetched and not yet shown: the next request
                // resumes from the unchanged cursor and consumes them.
                hasMore = true;
            } else if (!fetch.page().hasMore()) {
                next.markExhausted(fetch.stream());
            } else if (mine.isEmpty() && fetch.page().nextCursor() == null) {
                // Claims more, delivered nothing, and gave nothing to resume
                // from — the next request would be identical to this one. That
                // is the one shape that turns an endless scroll into an endless
                // loop, so the stream is retired instead of believed. Costs one
                // stream on a page; believing it costs the whole view.
                log.warn("Centauri: stream '{}' reports hasMore with an empty page and no "
                                + "nextCursor — retiring it for this scroll, since asking again "
                                + "would send the identical request. Fix the source: an empty "
                                + "page with hasMore must carry a cursor that moves.",
                        fetch.stream().key());
                next.markExhausted(fetch.stream());
            } else {
                hasMore = true;
            }
        }
        // A stream that did not answer has said nothing about whether it has
        // more, so the page must not claim the scroll is over on its behalf.
        // Settled silences are excluded on purpose: "not configured", "switched
        // off" and "does not declare that facet" are answers, and an answer may
        // end a round.
        for (StreamSilence silence : silences) {
            if (silence.isUnresolved()) {
                hasMore = true;
            }
        }
        // Streams already exhausted before this round were not fetched, so the
        // loop above never saw them — carry the flag forward explicitly.
        for (String key : incoming.exhausted()) {
            next.markExhausted(FeedStream.parseKey(key));
        }
        // publishedAt of the last *delivered* entry (spec §5.1), which is not
        // the cut whenever the page ended on a rejected candidate. The cut is
        // the cursor's business and already expressed per stream in
        // `perStream`; a second field describing "where the page ended" in a
        // subtly different way is how two readers of one bundle start to
        // disagree. Left untouched when a round delivered nothing — the
        // previous value still describes the last entry the reader saw.
        if (lastDelivered != null) {
            next.watermark(lastDelivered.item().publishedAt());
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
     * Keys worth keeping in the next cursor: every stream this round
     * <b>asked about</b> — answered or not — plus the ones already exhausted.
     * Anything else has been removed from the feed configuration and its
     * cursor is dead weight.
     *
     * <p>"Asked about" rather than "answered" is the whole point. Keying this
     * on {@code fetches} alone made a source that timed out or sat in a
     * cooldown indistinguishable from one the reader had deleted: its cursor
     * was dropped, and on the next page it started over at its newest entry
     * in the middle of a scroll.
     */
    private static Set<String> streamKeys(
            List<StreamFetch> fetches, List<StreamSilence> silences, CentauriCursor incoming) {
        Set<String> keys = new HashSet<>(incoming.exhausted());
        for (StreamFetch fetch : fetches) {
            keys.add(fetch.stream().key());
        }
        for (StreamSilence silence : silences) {
            keys.add(silence.stream().key());
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
