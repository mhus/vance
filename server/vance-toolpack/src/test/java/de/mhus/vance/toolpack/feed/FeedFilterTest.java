package de.mhus.vance.toolpack.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The filter carries two responsibilities that must not drift apart: which
 * parts a source may apply itself, and what the answer is for one entry.
 */
class FeedFilterTest {

    @Test
    void projectTo_keepsOnlyWhatTheSourceCanApply() {
        FeedFilter full = new FeedFilter(
                "berlin", Set.of("de"), List.of("rail"), List.of("advert"),
                Instant.parse("2026-08-01T00:00:00Z"));

        FeedFilter pushed = full.projectTo(caps(true, false, false));

        assertThat(pushed.text()).isEqualTo("berlin");
        assertThat(pushed.languages()).isEmpty();
        assertThat(pushed.since()).isNull();
        // Keyword lists are never pushed down — no source exposes a generic
        // keyword surface, and inventing one per protocol would make the same
        // filter mean different things per source.
        assertThat(pushed.include()).isEmpty();
        assertThat(pushed.exclude()).isEmpty();
    }

    @Test
    void needsPostFilter_isFalseOnlyWhenNothingRemains() {
        FeedFilter textOnly = new FeedFilter("berlin", Set.of(), List.of(), List.of(), null);

        assertThat(textOnly.needsPostFilter(caps(true, false, false))).isFalse();
        assertThat(textOnly.needsPostFilter(caps(false, false, false))).isTrue();
        assertThat(FeedFilter.none().needsPostFilter(caps(false, false, false))).isFalse();
    }

    @Test
    void needsPostFilter_isAlwaysTrueForKeywordLists() {
        FeedFilter withKeywords = new FeedFilter(
                null, Set.of(), List.of(), List.of("advert"), null);

        assertThat(withKeywords.needsPostFilter(caps(true, true, true))).isTrue();
    }

    @Test
    void matches_includeIsAnyOf_excludeIsNoneOf() {
        FeedFilter filter = new FeedFilter(
                null, Set.of(), List.of("rail", "tram"), List.of("advert"), null);

        assertThat(filter.matches(item("Tram line opens", null))).isTrue();
        assertThat(filter.matches(item("Bus line opens", null))).isFalse();
        assertThat(filter.matches(item("Tram advert campaign", null))).isFalse();
    }

    @Test
    void matches_itemWithoutDeclaredLanguage_passesALanguageFilter() {
        FeedFilter german = new FeedFilter(null, Set.of("de"), List.of(), List.of(), null);

        // Treating "unknown" as "wrong" would empty the stream completely for
        // any source that does not tag language — a strict filter would look
        // like a broken feed.
        assertThat(german.matches(item("Ohne Sprachangabe", null))).isTrue();
        assertThat(german.matches(item("Mit Angabe", "de-DE"))).isTrue();
        assertThat(german.matches(item("With tag", "en"))).isFalse();
    }

    @Test
    void matches_sinceExcludesOlderEntries() {
        FeedFilter recent = new FeedFilter(
                null, Set.of(), List.of(), List.of(), Instant.parse("2026-08-19T09:00:00Z"));

        assertThat(recent.matches(at("2026-08-19T10:00:00Z"))).isTrue();
        assertThat(recent.matches(at("2026-08-19T08:00:00Z"))).isFalse();
    }

    @Test
    void matches_textAlreadyAppliedBySource_isNotRepeatedLocally() {
        FeedFilter filter = new FeedFilter("tariffs", Set.of(), List.of(), List.of(), null);
        // The archive that prompted this indexes the article's own words and
        // delivers the translation, so the word it matched on is not in the text
        // it handed back. Re-checking locally dropped a hit the source found
        // correctly.
        FeedItem translated = item("Zölle auf Stahl", "de");

        assertThat(filter.matches(translated, filter.projectTo(caps(true, false, false))))
                .isTrue();
        // Without the pushdown it is our job again, and then it does not match.
        assertThat(filter.matches(translated, filter.projectTo(caps(false, false, false))))
                .isFalse();
    }

    @Test
    void matches_languageAlreadyAppliedBySource_isNotRepeatedLocally() {
        FeedFilter german = new FeedFilter(null, Set.of("de"), List.of(), List.of(), null);
        FeedItem english = item("Tariffs on steel", "en");

        assertThat(german.matches(english, german.projectTo(caps(false, true, false)))).isTrue();
        assertThat(german.matches(english, german.projectTo(caps(false, false, false)))).isFalse();
    }

    @Test
    void matches_excludeIsNeverDelegated() {
        FeedFilter filter = new FeedFilter(
                "steel", Set.of("de"), List.of(), List.of("advert"), null);
        // Everything this source could apply, it applied. "Do not show me this"
        // is still ours: delegating it would make a user's veto depend on a
        // foreign implementation getting it right.
        FeedFilter pushed = filter.projectTo(caps(true, true, true));

        assertThat(filter.matches(item("Steel advert campaign", "de"), pushed)).isFalse();
    }

    @Test
    void matches_sinceIsRepeatedEvenWhenPushedDown() {
        FeedFilter recent = new FeedFilter(
                null, Set.of(), List.of(), List.of(), Instant.parse("2026-08-19T09:00:00Z"));
        // publishedAt is the merge's ordering key, so every source delivers it
        // honestly — re-applying reads the same field the source filtered on and
        // costs nothing.
        FeedFilter pushed = recent.projectTo(caps(false, false, true));

        assertThat(recent.matches(at("2026-08-19T08:00:00Z"), pushed)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static FeedCapabilities caps(boolean text, boolean language, boolean since) {
        return new FeedCapabilities(
                FeedSelectorMode.NONE, Set.of(), text, language, since,
                false, true, 40, Set.of(), false, Duration.ofMinutes(5));
    }

    private static FeedItem item(String title, String language) {
        return new FeedItem("i1", /* cursor */ null, Instant.parse("2026-08-19T10:00:00Z"), title,
                "https://x.test/1", null, null, null, language, null, null,
                List.of(), Map.of());
    }

    private static FeedItem at(String isoInstant) {
        return new FeedItem("i1", /* cursor */ null, Instant.parse(isoInstant), "title",
                "https://x.test/1",
                null, null, null, null, null, null, List.of(), Map.of());
    }
}
