package de.mhus.vance.toolpack.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a source refuses to hand on. Pure logic — the part that decides whether
 * an entry reaches a reader, so it is tested directly.
 */
class FeedContentPolicyTest {

    // ──── Reading it off the source document ────────────────────────────

    /** A source that declares nothing has no policy and blocks nothing. */
    @Test
    void from_emptyConfig_isEmpty() {
        assertThat(FeedContentPolicy.from(config(Map.of())).isEmpty()).isTrue();
    }

    /**
     * The whole reason lists are comma-separated text: this is the obvious way
     * to write a single entry, and as a YAML list it would be a string where a
     * list is expected — a cast error or a silently ignored line.
     */
    @Test
    void from_singleHostWithoutList_isRead() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("blockedHosts", "manporn.top")));

        assertThat(policy.blockedHosts()).containsExactly("manporn.top");
    }

    @Test
    void from_commaSeparated_trimsAndDropsBlanks() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("blockedHosts", " a.example , ,b.example,")));

        assertThat(policy.blockedHosts()).containsExactlyInAnyOrder("a.example", "b.example");
    }

    @Test
    void from_hostsAreCaseInsensitive() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("blockedHosts", "Example.ORG")));

        assertThat(policy.allows(item("https://example.org/@a/1", null, false))).isFalse();
    }

    // ──── sensitive ─────────────────────────────────────────────────────

    /** The precise lever: the source labelled it, we take it at its word. */
    @Test
    void allows_sensitiveEntry_isDroppedWhenConfigured() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("hideSensitive", true)));

        assertThat(policy.allows(item("https://mastodon.social/@a/1", null, true))).isFalse();
        assertThat(policy.allows(item("https://mastodon.social/@a/2", null, false))).isTrue();
    }

    /** Off by default: existing sources must not start dropping entries. */
    @Test
    void allows_sensitiveEntry_passesWhenNotConfigured() {
        assertThat(FeedContentPolicy.none()
                .allows(item("https://mastodon.social/@a/1", null, true))).isTrue();
    }

    // ──── Hosts ─────────────────────────────────────────────────────────

    /** Without this the way around a blocklist is one CNAME. */
    @Test
    void allows_subdomainOfBlockedHost_isDropped() {
        FeedContentPolicy policy = new FeedContentPolicy(false, Set.of("example.org"), Set.of());

        assertThat(policy.allows(item("https://www.example.org/@a/1", null, false))).isFalse();
        assertThat(policy.allows(item("https://a.b.example.org/@a/1", null, false))).isFalse();
    }

    /** The dot in the suffix test is what keeps this one out of it. */
    @Test
    void allows_hostThatMerelyEndsInTheSameLetters_passes() {
        FeedContentPolicy policy = new FeedContentPolicy(false, Set.of("example.org"), Set.of());

        assertThat(policy.allows(item("https://notexample.org/@a/1", null, false))).isTrue();
    }

    /**
     * An unreadable address is not a reason to drop an entry — that would be a
     * filter nobody configured.
     */
    @Test
    void allows_unparseableUrl_passes() {
        FeedContentPolicy policy = new FeedContentPolicy(false, Set.of("example.org"), Set.of());

        assertThat(policy.allows(item("not a url", null, false))).isTrue();
    }

    // ──── Authors ───────────────────────────────────────────────────────

    /** For the single spammer on an otherwise good instance. */
    @Test
    void allows_blockedAuthor_isDropped() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("blockedAuthors", "@spammer@mastodon.social")));

        assertThat(policy.allows(
                item("https://mastodon.social/@spammer/1", "@spammer@mastodon.social", false)))
                .isFalse();
        assertThat(policy.allows(
                item("https://mastodon.social/@other/1", "@other@mastodon.social", false)))
                .isTrue();
    }

    @Test
    void allows_entryWithoutAuthor_passesTheAuthorCheck() {
        FeedContentPolicy policy = FeedContentPolicy.from(
                config(Map.of("blockedAuthors", "@spammer@mastodon.social")));

        assertThat(policy.allows(item("https://example.org/1", null, false))).isTrue();
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static FeedInstanceConfig config(Map<String, Object> extras) {
        return new FeedInstanceConfig(
                "src", "mastodon", "https://mastodon.social", "", () -> null, extras);
    }

    private static FeedItem item(String url, String author, boolean sensitive) {
        return new FeedItem(
                "id-" + url.hashCode(), null, Instant.parse("2026-08-23T10:00:00Z"),
                "title", url, null, null, author, null, null, null, List.of(),
                sensitive ? Map.of("sensitive", true) : Map.of());
    }
}
