package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One search as the surface asks for it.
 *
 * <p>A POST body rather than query parameters because {@code expertParams} is a
 * structured map — squeezing it into a query string would invent an encoding
 * both ends then have to agree on.
 *
 * @param num      hits wanted; absent or non-positive means the manifest default.
 *                 Boxed because absence is a legitimate answer and a primitive
 *                 cannot express it.
 * @param tier     {@code normal} or {@code expert}; unreadable values fall back
 *                 to normal rather than refusing a search over a typo.
 * @param instance pins one provider endpoint, bypassing the default/fallback
 *                 cascade. Expert tier only — the dispatcher ignores it otherwise.
 */
@GenerateTypeScript("search")
public record SearchRequestView(
        String query,
        @Nullable String modality,
        @Nullable String tier,
        @Nullable Integer num,
        @Nullable String locale,
        @Nullable String instance,
        @Nullable Map<String, Object> expertParams,
        /**
         * Facet selection, {@code key -> values}. Unlike {@code expertParams}
         * this is not tier-gated: a facet is structured and declared, so it
         * means the same thing at every tier — and a provider that has not
         * declared a selected key is skipped rather than ignoring it.
         */
        @Nullable Map<String, List<String>> facets) {

    /** The same request without facets. */
    public SearchRequestView(
            String query,
            @Nullable String modality,
            @Nullable String tier,
            @Nullable Integer num,
            @Nullable String locale,
            @Nullable String instance,
            @Nullable Map<String, Object> expertParams) {
        this(query, modality, tier, num, locale, instance, expertParams, null);
    }
}
