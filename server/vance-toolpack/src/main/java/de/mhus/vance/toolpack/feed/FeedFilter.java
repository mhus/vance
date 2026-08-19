package de.mhus.vance.toolpack.feed;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the reader wants to see. Structured on purpose — a query language
 * would mean a parser, a validator and a translation per source, while
 * these fields map straight onto the existing form engine and are written
 * correctly by an LLM.
 *
 * <p>Two halves of the same filter:
 * <ul>
 *   <li>{@link #projectTo(FeedCapabilities)} yields the subset a given
 *       source can apply itself, which travels in {@link FeedFetch#pushdown()}.
 *   <li>{@link #matches(FeedItem)} applies the <b>whole</b> filter after the
 *       fetch.
 * </ul>
 *
 * <p>Post-filtering always re-applies everything, including the parts that
 * were pushed down. That is deliberate: filtering twice is idempotent and
 * free, while tracking which half still needs applying is bookkeeping that
 * fails silently the day a capability flag and a wire implementation
 * disagree. The rule that must hold is "no filter is ever skipped", and
 * the cheapest way to guarantee it is to not rely on the source at all.
 */
public record FeedFilter(
        @Nullable String text,
        Set<String> languages,
        List<String> include,
        List<String> exclude,
        @Nullable Instant since) {

    public FeedFilter {
        text = blankToNull(text);
        languages = languages == null ? Set.of() : normalize(languages);
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public static FeedFilter none() {
        return new FeedFilter(null, Set.of(), List.of(), List.of(), null);
    }

    public boolean isEmpty() {
        return text == null && languages.isEmpty() && include.isEmpty()
                && exclude.isEmpty() && since == null;
    }

    /**
     * The subset this source can apply itself. Everything else is left to
     * {@link #matches(FeedItem)}.
     *
     * <p>{@code include}/{@code exclude} are never pushed down: no source
     * exposes a generic keyword-list surface, and inventing one per
     * protocol would make the same filter mean different things per source.
     */
    public FeedFilter projectTo(FeedCapabilities caps) {
        return new FeedFilter(
                caps.pushdownTextSearch() ? text : null,
                caps.pushdownLanguage() ? languages : Set.of(),
                List.of(),
                List.of(),
                caps.pushdownSince() ? since : null);
    }

    /**
     * True when this filter has parts the source cannot apply — the signal
     * for the merge to over-fetch, because otherwise a page of twenty
     * shrinks to three.
     */
    public boolean needsPostFilter(FeedCapabilities caps) {
        if (isEmpty()) {
            return false;
        }
        if (!include.isEmpty() || !exclude.isEmpty()) {
            return true;
        }
        if (text != null && !caps.pushdownTextSearch()) {
            return true;
        }
        if (!languages.isEmpty() && !caps.pushdownLanguage()) {
            return true;
        }
        return since != null && !caps.pushdownSince();
    }

    /** Apply the whole filter to one item. */
    public boolean matches(FeedItem item) {
        if (since != null && item.publishedAt().isBefore(since)) {
            return false;
        }
        if (!matchesLanguage(item)) {
            return false;
        }
        String haystack = haystack(item);
        for (String term : exclude) {
            if (contains(haystack, term)) {
                return false;
            }
        }
        if (text != null && !contains(haystack, text)) {
            return false;
        }
        if (include.isEmpty()) {
            return true;
        }
        for (String term : include) {
            if (contains(haystack, term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An item whose language the source did not declare passes a language
     * filter. Treating "unknown" as "wrong" would empty the stream
     * completely for any source that does not tag language at all — the
     * filter would look like a broken feed rather than a strict one.
     */
    private boolean matchesLanguage(FeedItem item) {
        if (languages.isEmpty()) {
            return true;
        }
        String lang = item.language();
        if (lang == null || lang.isBlank()) {
            return true;
        }
        return languages.contains(baseLanguage(lang));
    }

    private static String haystack(FeedItem item) {
        StringBuilder sb = new StringBuilder(item.title());
        if (item.summary() != null) {
            sb.append('\n').append(item.summary());
        }
        if (item.body() != null) {
            sb.append('\n').append(item.body());
        }
        for (String tag : item.tags()) {
            sb.append('\n').append(tag);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String lowercaseHaystack, String term) {
        String needle = term.trim().toLowerCase(Locale.ROOT);
        return !needle.isEmpty() && lowercaseHaystack.contains(needle);
    }

    /** {@code de-DE} and {@code de} are the same language for filtering. */
    private static String baseLanguage(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('-');
        return cut > 0 ? lower.substring(0, cut) : lower;
    }

    private static Set<String> normalize(Set<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(baseLanguage(s));
            }
        }
        return Set.copyOf(out);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
