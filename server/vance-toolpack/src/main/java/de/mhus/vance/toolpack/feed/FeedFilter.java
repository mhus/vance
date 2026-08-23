package de.mhus.vance.toolpack.feed;

import de.mhus.vance.toolpack.facet.FacetSelection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>{@link #matches(FeedItem, FeedFilter)} applies the rest after the
 *       fetch.
 * </ul>
 *
 * <p>{@link #facets()} sits outside that split: it is pushdown-only. Either
 * the source declared the facet, then it filtered on it, or it did not, then
 * {@link #undeclaredFacets(FeedCapabilities)} says so and the source is left
 * out of the request. There is no third state, because an entry carries no
 * facet values to check against — that half of the design was considered and
 * dropped ({@code planning/centauri-facets.md} §3.3).
 *
 * <h2>Why the post-filter does not simply re-apply everything</h2>
 *
 * <p>It used to, on the reasoning that filtering twice is idempotent and free
 * while tracking halves is bookkeeping that drifts. That reasoning holds only
 * for a criterion whose local check reads the same text the source searched —
 * and for two of them it demonstrably does not:
 *
 * <ul>
 *   <li><b>text.</b> A source may index fields it does not deliver. The
 *       archive that prompted this indexes an article's original title and
 *       teaser but delivers the <em>translation</em>: it correctly returns a
 *       hit for {@code tariffs}, the local re-check looks for that word in a
 *       German title and drops it. Re-applying turned "found by one of two
 *       words" into "found by neither".
 *   <li><b>languages.</b> Same shape — the source may match the original
 *       language and hand back an entry labelled with the pivot.
 * </ul>
 *
 * <p>So a criterion the source <em>actually applied</em> is not re-applied.
 * That is keyed on what was sent ({@link #projectTo}'s result), not on a
 * capability flag, so a source cannot get a criterion skipped by declaring an
 * ability it was never asked to use. {@code since} stays re-applied — it reads
 * {@code publishedAt}, which every source must deliver honestly because the
 * merge orders on it — and {@code include}/{@code exclude} are never pushed
 * down and therefore always applied here. In particular {@code exclude} is
 * never delegated: "never show me this" must not depend on a foreign
 * implementation.
 */
public record FeedFilter(
        @Nullable String text,
        Set<String> languages,
        List<String> include,
        List<String> exclude,
        @Nullable Instant since,
        /**
         * Facet selection, {@code key → values} — conjunction across keys,
         * disjunction within one. See
         * {@link de.mhus.vance.toolpack.facet.FacetSelection}.
         *
         * <p>The odd one out among these fields: it is never post-filtered.
         * A source either declared the facet, then it applied it, or it did
         * not, then it was left out of the request altogether. There is
         * nothing on the entry to check it against, deliberately — see
         * {@code planning/centauri-facets.md} §3.3.
         */
        Map<String, List<String>> facets) {

    public FeedFilter {
        text = blankToNull(text);
        languages = languages == null ? Set.of() : normalize(languages);
        // Blank terms are dropped rather than kept: `contains` refuses an empty
        // needle, so a single `include = [""]` — reachable over REST and over
        // `feed_read`, only the manifest path filtered it — made every entry
        // fail the include check and emptied the feed with nothing to explain
        // it. `languages` was normalised here from the start; these two were
        // not, and the asymmetry was the whole bug.
        include = normalizeTerms(include);
        exclude = normalizeTerms(exclude);
        facets = FacetSelection.normalize(facets);
    }

    /** The same filter without facets — the shape that predates them. */
    public FeedFilter(
            @Nullable String text,
            Set<String> languages,
            List<String> include,
            List<String> exclude,
            @Nullable Instant since) {
        this(text, languages, include, exclude, since, FacetSelection.none());
    }

    public static FeedFilter none() {
        return new FeedFilter(null, Set.of(), List.of(), List.of(), null,
                FacetSelection.none());
    }

    public boolean isEmpty() {
        return text == null && languages.isEmpty() && include.isEmpty()
                && exclude.isEmpty() && since == null && facets.isEmpty();
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
                caps.pushdownSince() ? since : null,
                FacetSelection.restrictTo(facets, caps.facetKeys()));
    }

    /**
     * Selected facet keys this source did not declare. Non-empty means the
     * source cannot answer the question that was asked and is left out of the
     * request — not silently: the dispatcher turns this into a visible note.
     *
     * <p>The opposite of the {@code languages} rule one method below, and for
     * a reason: a language is a property every entry has and may merely fail
     * to state, while a place or a topic is a claim that either holds or does
     * not. Letting a source that never heard of places through a „show me
     * Asia" filter would make the filter look broken rather than strict.
     */
    public List<String> undeclaredFacets(FeedCapabilities caps) {
        return FacetSelection.undeclaredKeys(facets, caps.facetKeys());
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

    /**
     * Apply the whole filter to one item — for a source that pushed nothing
     * down, and for callers filtering a list they assembled themselves.
     */
    public boolean matches(FeedItem item) {
        return matches(item, none());
    }

    /**
     * Apply the parts of this filter that {@code appliedBySource} did not
     * already answer.
     *
     * @param appliedBySource exactly what was sent to the source, i.e. the
     *                        result of {@link #projectTo(FeedCapabilities)}.
     *                        Keyed on what was sent rather than on the
     *                        capability flag, so declaring an ability cannot by
     *                        itself get a criterion skipped. See the class note
     *                        for why text and language are not re-applied.
     */
    public boolean matches(FeedItem item, FeedFilter appliedBySource) {
        if (since != null && item.publishedAt().isBefore(since)) {
            return false;
        }
        if (appliedBySource.languages().isEmpty() && !matchesLanguage(item)) {
            return false;
        }
        String haystack = haystack(item);
        // exclude first, and never delegated: "do not show me this" must not
        // depend on a foreign implementation getting it right.
        for (String term : exclude) {
            if (contains(haystack, term)) {
                return false;
            }
        }
        if (text != null && appliedBySource.text() == null && !contains(haystack, text)) {
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

    /** Trimmed keyword list without blanks — see the compact constructor. */
    private static List<String> normalizeTerms(@Nullable List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return List.copyOf(out);
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
