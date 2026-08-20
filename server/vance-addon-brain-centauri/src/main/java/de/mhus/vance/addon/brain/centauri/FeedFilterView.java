package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The stored filter. Structured, not a query language — these fields map
 * straight onto the form and an LLM writes them without a parser.
 *
 * <p>{@code since} is relative ({@code -7d}) so it keeps meaning as it ages; an
 * absolute instant is accepted for the case where one fixed date is meant.
 */
@GenerateTypeScript("centauri")
public record FeedFilterView(
        @Nullable String text,
        List<String> languages,
        List<String> include,
        List<String> exclude,
        @Nullable String since,
        /**
         * Facet selection, {@code key -> values}. Conjunction across keys,
         * disjunction within one.
         *
         * <p>The one filter field a source can refuse: a stream whose source
         * does not declare a selected key is left out of the page with a
         * note, rather than being asked and filtered afterwards.
         */
        Map<String, List<String>> facets) {}
