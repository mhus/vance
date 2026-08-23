package de.mhus.vance.addon.brain.mastodon;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * When an entry appeared <b>in this stream</b> — which is not
 * {@code created_at}.
 *
 * <p>Measured on 2026-08-23 (see {@code planning/centauri-mastodon-messung.md}
 * §3): on one page of 40 entries of a federated timeline, {@code created_at}
 * was out of order at 17 of 39 adjacent pairs, with deltas up to <b>36 hours</b>
 * — a Flipboard bridge posting an article it had queued a day and a half
 * earlier. A timeline is ordered by <em>local ingest</em>, and the id is what
 * carries that: Mastodon status ids are snowflakes whose upper bits are
 * milliseconds since epoch. In id order the same page was exactly monotonic.
 *
 * <p>This matters because Centauri promises stable {@code publishedAt desc}
 * ordering across pages while paging by id. With {@code created_at} as
 * {@code publishedAt}, an entry on page four sorts onto page one — a page the
 * cursor has long passed. No error, no log; just a chronology that jumps.
 *
 * <p>Two levels of defence, because the snowflake layout is a Mastodon
 * implementation detail and not part of the API:
 *
 * <ol>
 *   <li>decode the id, but only when it is decimal and the result is a
 *       plausible instant — Pleroma/Akkoma serve base62 FlakeId strings
 *       ({@code B9f00nttYL428fNa8e}) and Friendica small integers;
 *   <li>failing that, fall back to {@code created_at} <b>clamped</b> so the
 *       page cannot go backwards.
 * </ol>
 *
 * <p>The clamp is correct within a page and approximate across page
 * boundaries; for Mastodon proper it never runs.
 */
final class MastodonStreamTime {

    /** Mastodon snowflake: {@code (millis << 16) | sequence}. */
    private static final int SEQUENCE_BITS = 16;

    /** Below this, a decoded id is not a timestamp but a small integer. */
    private static final Instant PLAUSIBLE_FROM = Instant.parse("2010-01-01T00:00:00Z");

    /** Above this, it is not a timestamp either. */
    private static final Instant PLAUSIBLE_UNTIL = Instant.parse("2100-01-01T00:00:00Z");

    private MastodonStreamTime() {
        /* helpers only */
    }

    /**
     * The ingest instant encoded in a status id, or null when this id does not
     * carry one.
     */
    static @Nullable Instant fromId(@Nullable String id) {
        if (id == null || id.isEmpty() || id.length() > 19) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return null;
            }
        }
        long millis;
        try {
            millis = Long.parseLong(id) >>> SEQUENCE_BITS;
        } catch (NumberFormatException e) {
            return null;
        }
        Instant candidate = Instant.ofEpochMilli(millis);
        return candidate.isBefore(PLAUSIBLE_FROM) || candidate.isAfter(PLAUSIBLE_UNTIL)
                ? null
                : candidate;
    }

    /**
     * The stream time of one entry: the id-derived instant when there is one,
     * otherwise {@code createdAt} held at or below {@code previous}.
     *
     * <p>{@code previous} is the stream time of the entry delivered just before
     * this one, or null for the first of a page.
     */
    static Instant of(String id, Instant createdAt, @Nullable Instant previous) {
        Instant fromId = fromId(id);
        if (fromId != null) {
            return fromId;
        }
        if (previous != null && createdAt.isAfter(previous)) {
            return previous;
        }
        return createdAt;
    }

    /**
     * True when {@code instants} never rises — the property the merge relies
     * on. Exists for the tests, which is the only place the whole page is
     * available at once.
     */
    static boolean isDescending(List<Instant> instants) {
        for (int i = 0; i + 1 < instants.size(); i++) {
            if (instants.get(i).isBefore(instants.get(i + 1))) {
                return false;
            }
        }
        return true;
    }
}
