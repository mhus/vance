package de.mhus.vance.toolpack.research;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One hit that survived the {@code research_investigate} evaluation
 * pass. Carries the original {@link SearchHit} fields plus the
 * scoring breakdown the evaluate-recipe produced.
 *
 * <p>{@code finalScore = relevanceScore * sourceAffinityApplied} —
 * the multiplication happens in {@code ZarniwoopResearchService}; the
 * recipe only delivers the relevance number on a 0..1 scale.
 */
public record RankedHit(
        String title,
        String url,
        double finalScore,
        double relevanceScore,
        double sourceAffinityApplied,
        SearchModality modality,
        String providerInstanceId,
        @Nullable String snippet,
        @Nullable String relevanceNote,
        Map<String, Object> extras) {

    public RankedHit {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (modality == null) {
            throw new IllegalArgumentException("modality is required");
        }
        if (providerInstanceId == null || providerInstanceId.isBlank()) {
            throw new IllegalArgumentException("providerInstanceId is required");
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
