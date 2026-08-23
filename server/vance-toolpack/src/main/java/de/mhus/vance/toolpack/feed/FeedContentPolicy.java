package de.mhus.vance.toolpack.feed;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a source refuses to hand on, regardless of what was asked for.
 *
 * <p><b>Not a {@link FeedFilter}.</b> A filter is a <em>query</em> — what I want
 * to see right now; it changes with the interest and arrives per request from
 * the feed app, from REST, from the {@code feed_read} tool. This is
 * <em>standing policy</em> — what must never come through. Were it a filter
 * field, every caller would have to send it, and whoever does not know it does
 * not have it: the LLM behind {@code feed_read} certainly does not. <b>A filter
 * one can forget is not a policy.</b>
 *
 * <p>Because it hangs on the source rather than on the request, "cannot be
 * bypassed" follows from the structure instead of from a rule somebody has to
 * enforce. A filter may narrow further; it can never widen past this.
 *
 * <p>Read from the source document's own fields — see
 * {@link #from(FeedInstanceConfig)}. A protocol that sets none of them has no
 * policy and pays one lookup for it.
 *
 * <p><b>This is not a parental control.</b> A blocklist blocks what is on it;
 * new instances keep appearing. The effective lever against unwanted content in
 * a federated stream is the <em>selector</em> — a local or hashtag timeline
 * never pulls foreign instances in. See
 * {@code planning/centauri-content-policy.md} §2.1 and §6.
 */
public record FeedContentPolicy(
        /**
         * Drop entries the source itself marked as sensitive, i.e. carrying
         * {@code extras.sensitive == true}.
         *
         * <p>The most precise of the three levers: author and instance label
         * their own posts, so nothing has to be guessed, and it covers
         * instances nobody has put on a list yet. The key is a convention open
         * to every protocol — one that does not know it never sets the flag,
         * and the check is simply inert there rather than a special case.
         */
        boolean hideSensitive,
        /**
         * Hosts whose entries are dropped, lower-cased. <b>Subdomains
         * included</b> — {@code manporn.top} also blocks
         * {@code www.manporn.top}, or the way around it is one CNAME.
         */
        Set<String> blockedHosts,
        /** Authors whose entries are dropped, lower-cased, exact match. */
        Set<String> blockedAuthors) {

    /** Source field: drop entries flagged sensitive by the source. */
    public static final String FIELD_HIDE_SENSITIVE = "hideSensitive";

    /** Source field: comma-separated hosts. */
    public static final String FIELD_BLOCKED_HOSTS = "blockedHosts";

    /** Source field: comma-separated authors. */
    public static final String FIELD_BLOCKED_AUTHORS = "blockedAuthors";

    /** Item extra a protocol sets when the source flagged the entry. */
    public static final String EXTRA_SENSITIVE = "sensitive";

    private static final FeedContentPolicy NONE =
            new FeedContentPolicy(false, Set.of(), Set.of());

    public FeedContentPolicy {
        blockedHosts = lowerCased(blockedHosts);
        blockedAuthors = lowerCased(blockedAuthors);
    }

    /** No policy — nothing is dropped. */
    public static FeedContentPolicy none() {
        return NONE;
    }

    /**
     * Reads the policy off a source's own configuration.
     *
     * <p>The fields sit directly in the source document, next to
     * {@code protocol} and {@code baseUrl}, and arrive here through
     * {@code SourceConfig.extras} — "everything else the document declares".
     * No schema change was needed for that, and no {@code params:} wrapper:
     * that would be a second level for something that already works flat, and
     * would raise the question what happens to a field written <em>beside</em>
     * it instead of inside.
     *
     * <p>Deliberately per source, not one global list: {@code hideSensitive}
     * hangs on a flag only Mastodon-like sources set, and a federating
     * instance can only turn up in a federated stream. A global list would be
     * evaluated against every source while applying to one class of them — and
     * a second file is a second place to find when the question is "why am I
     * not seeing this post".
     */
    public static FeedContentPolicy from(FeedInstanceConfig config) {
        return new FeedContentPolicy(
                Boolean.parseBoolean(config.extra(FIELD_HIDE_SENSITIVE, "false")),
                commaSeparated(config.extra(FIELD_BLOCKED_HOSTS, "")),
                commaSeparated(config.extra(FIELD_BLOCKED_AUTHORS, "")));
    }

    /** {@code true} when nothing is configured — lets callers skip the work. */
    public boolean isEmpty() {
        return !hideSensitive && blockedHosts.isEmpty() && blockedAuthors.isEmpty();
    }

    /**
     * Whether this entry may be handed on.
     *
     * <p>A rejected entry is <b>not</b> an error and must still advance the
     * cursor — otherwise a stream of nothing but blocked entries scrolls
     * forever without progress. That is the same treatment a filter rejection
     * gets, and the merge already handles it.
     */
    public boolean allows(FeedItem item) {
        if (isEmpty()) {
            return true;
        }
        if (hideSensitive && isSensitive(item)) {
            return false;
        }
        if (!blockedHosts.isEmpty() && isBlockedHost(hostOf(item.url()))) {
            return false;
        }
        String author = item.author();
        return author == null
                || !blockedAuthors.contains(author.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isSensitive(FeedItem item) {
        Object flag = item.extras().get(EXTRA_SENSITIVE);
        return flag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(flag));
    }

    /**
     * Host match including subdomains: an entry from {@code www.example.xxx} is
     * blocked by {@code example.xxx}. The dot in the suffix test is what keeps
     * {@code notexample.xxx} out of it.
     */
    private boolean isBlockedHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        for (String blocked : blockedHosts) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host of an entry's url, or {@code null} when it has none.
     *
     * <p>The url is the key rather than the author because it is a required,
     * structured field while {@code author} is free text — which also makes the
     * blocklist work for every source, not just for Mastodon. An unparseable
     * url is <b>not</b> blocked: dropping entries because we could not read
     * their address would be a filter nobody configured.
     */
    private static @Nullable String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Splits a comma-separated list, trimming and dropping blanks.
     *
     * <p>Comma-separated text rather than a YAML list, for two reasons. It is
     * how deny-lists are written in this tree already
     * ({@code vance.settings.secretReferenceDenyKeys} and its sibling), and a
     * YAML list has a one-element trap: {@code blockedHosts: example.xxx}
     * without a dash is legal YAML, yields a string rather than a list, and is
     * the obvious way to write it — with a list expected that is a cast error
     * or a silently ignored line.
     */
    private static Set<String> commaSeparated(String raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static Set<String> lowerCased(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String s : source) {
            if (s != null && !s.isBlank()) {
                result.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }
}
