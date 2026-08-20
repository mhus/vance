package de.mhus.vance.toolpack.facet;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dimension a source can be filtered by, declared in its capabilities.
 *
 * <p><b>Declaring means being able to filter.</b> There is no „I label but do
 * not query" flag: it is the same field in the source's own store, and one
 * that can write it to an entry can search on it. The alternative would be a
 * second filter path in the reader for a case no source has — and for search
 * it could not work at all, because a ranked answer has no cursor to fetch the
 * over-filtered remainder from.
 *
 * <p>Consequently a facet the source does not declare is not filtered locally:
 * the source is left out of that request entirely, visibly. See
 * {@code planning/centauri-facets.md} §4.
 *
 * <h2>Reserved keys</h2>
 *
 * <p>A reserved key carries a normative value system, because otherwise two
 * sources share only the name and the reader merges two different questions
 * into one checkbox:
 *
 * <ul>
 *   <li>{@code origin-place} / {@code subject-place} — {@code m49:} above the
 *       country, {@code iso:} at it. Where it was <em>published</em> versus
 *       what it is <em>about</em>: a single {@code place} would mean the
 *       publisher's seat at one source and the subject at the next, and the
 *       difference shows up exactly in international coverage.
 *   <li>{@code origin-topic} / {@code subject-topic} — <b>no</b> normative
 *       vocabulary yet, therefore source-specific: „gaming" at one source and
 *       „games" at another are not the same selection, and the UI renders them
 *       per source rather than pretending otherwise.
 * </ul>
 *
 * <p>Any other key is a source's own and filters only its own streams.
 */
public record Facet(
        String key,
        String label,
        boolean hierarchical,
        List<FacetValue> values,
        boolean lazyChildren) {

    private static final Logger log = LoggerFactory.getLogger(Facet.class);

    /**
     * How many values may travel inline in a capabilities response.
     *
     * <p>The list hangs in <em>every</em> capabilities answer, is cached for
     * the source's TTL and lands in every configuration form. Beyond this a
     * source has to serve its tree level by level ({@code lazyChildren}).
     *
     * <p>Enforced differently on the two ends on purpose: the source-side
     * record in {@code vance-ode-core} throws, because its author can fix it
     * where it happens; here — parsing a foreign declaration — it truncates
     * and warns, because a contract breach on the far end must not take the
     * source out of the reader's view.
     */
    public static final int MAX_INLINE_VALUES = 500;

    public Facet {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("facet key is required");
        }
        key = key.trim();
        if (key.indexOf('.') >= 0) {
            // The selection map is persisted inside an application manifest,
            // and MongoDB reads a dot in a map key as a path separator.
            throw new IllegalArgumentException(
                    "facet key must not contain '.' (was '" + key + "') — use '-'");
        }
        label = label == null || label.isBlank() ? key : label.trim();
        values = values == null ? List.of() : List.copyOf(values);
        if (values.size() > MAX_INLINE_VALUES) {
            log.warn("Facet '{}' declares {} inline values, keeping the first {} — "
                            + "a source with more has to serve them lazily",
                    key, values.size(), MAX_INLINE_VALUES);
            values = List.copyOf(values.subList(0, MAX_INLINE_VALUES));
        }
    }

    /** A flat facet whose values are all there is. */
    public static Facet flat(String key, String label, List<FacetValue> values) {
        return new Facet(key, label, false, values, false);
    }
}
