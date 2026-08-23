package de.mhus.vance.addon.brain.mastodon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the decision from {@code planning/centauri-mastodon.md} §2a: the sort
 * key is the ingest time carried by the id, not {@code created_at}.
 *
 * <p>Every id and timestamp below is real, taken from the measurement of
 * 2026-08-23 (see {@code planning/centauri-mastodon-messung.md} §6). Invented
 * fixtures would not have produced the 36-hour skew that makes this necessary.
 */
class MastodonStreamTimeTest {

    /** mastodon.scot, federated a second after it was written. */
    private static final String ID_FRESH = "117144208605002329";

    /** A Flipboard bridge posting an article queued 36 hours earlier. */
    private static final String ID_BRIDGED = "117144208580248837";

    /** A local post of the server we asked. */
    private static final String ID_LOCAL = "117144208450561132";

    @Test
    void fromId_decodesTheSnowflakeIngestInstant() {
        assertThat(MastodonStreamTime.fromId(ID_FRESH))
                .isEqualTo(Instant.parse("2026-08-23T09:52:48.997Z"));
        assertThat(MastodonStreamTime.fromId(ID_BRIDGED))
                .isEqualTo(Instant.parse("2026-08-23T09:52:48.619Z"));
    }

    @Test
    void fromId_forALocalPostMatchesCreatedAtToTheMillisecond() {
        // The strongest evidence that the decode is right rather than merely
        // monotonic: for a post written on this very server the two agree
        // exactly. The skew comes from federation, not from the arithmetic.
        assertThat(MastodonStreamTime.fromId(ID_LOCAL))
                .isEqualTo(Instant.parse("2026-08-23T09:52:46.640Z"));
    }

    @Test
    void fromId_refusesIdsThatAreNotMastodonSnowflakes() {
        // Pleroma/Akkoma serve base62 FlakeIds — measured on pleroma.envs.net.
        assertThat(MastodonStreamTime.fromId("B9f00nttYL428fNa8e")).isNull();
        // Friendica-style small integers decode to 1970 and are refused by the
        // plausibility bound rather than accepted as "the epoch".
        assertThat(MastodonStreamTime.fromId("12345")).isNull();
        assertThat(MastodonStreamTime.fromId("")).isNull();
        assertThat(MastodonStreamTime.fromId(null)).isNull();
        // Longer than any long — must not throw.
        assertThat(MastodonStreamTime.fromId("99999999999999999999999")).isNull();
    }

    @Test
    void of_prefersTheIdOverCreatedAt() {
        Instant createdAt = Instant.parse("2026-08-21T21:53:33Z");

        Instant time = MastodonStreamTime.of(ID_BRIDGED, createdAt, null);

        assertThat(time).isEqualTo(Instant.parse("2026-08-23T09:52:48.619Z"));
        assertThat(time).isAfter(createdAt);
    }

    @Test
    void of_withoutAUsableIdClampsToThePreviousEntry() {
        Instant previous = Instant.parse("2026-08-23T09:00:00Z");

        // A FlakeId source whose entry claims to be newer than the one before
        // it: honouring that would make the page rise and break the merge's
        // ordering guarantee.
        Instant clamped = MastodonStreamTime.of(
                "B9f00nttYL428fNa8e", Instant.parse("2026-08-23T10:00:00Z"), previous);
        assertThat(clamped).isEqualTo(previous);

        // An entry that is genuinely older keeps its own timestamp.
        Instant kept = MastodonStreamTime.of(
                "B9f00nttYL428fNa8e", Instant.parse("2026-08-23T08:00:00Z"), previous);
        assertThat(kept).isEqualTo(Instant.parse("2026-08-23T08:00:00Z"));
    }

    @Test
    void of_theMeasuredPageIsDescendingByIdTimeAndNotByCreatedAt() {
        List<String> ids = List.of(ID_FRESH, ID_BRIDGED, ID_LOCAL);
        List<Instant> createdAt = List.of(
                Instant.parse("2026-08-23T09:52:48Z"),
                Instant.parse("2026-08-21T21:53:33Z"),   // the bridge, 36 h old
                Instant.parse("2026-08-23T09:52:46.640Z"));

        List<Instant> streamTimes = new java.util.ArrayList<>();
        Instant previous = null;
        for (int i = 0; i < ids.size(); i++) {
            previous = MastodonStreamTime.of(ids.get(i), createdAt.get(i), previous);
            streamTimes.add(previous);
        }

        // This is the bug the whole class exists to prevent: delivered in this
        // order, created_at goes UP between entry 2 and 3.
        assertThat(MastodonStreamTime.isDescending(createdAt)).isFalse();
        assertThat(MastodonStreamTime.isDescending(streamTimes)).isTrue();
    }
}
