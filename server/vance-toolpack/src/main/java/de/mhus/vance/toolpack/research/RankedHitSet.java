package de.mhus.vance.toolpack.research;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Return shape of {@code ZarniwoopResearchService.investigate(...)}.
 * Curated result of one research run: ranked hits, dropped hits with
 * reasons, plus the plan-recipe's source-affinity map so the caller
 * can see how the dispatcher weighted each modality / instance.
 *
 * <p>v1 carries no refine-depth (refining is v1.5). The
 * {@code refineDepth} field is reserved at 0; callers can ignore it
 * until the refine loop ships.
 */
public record RankedHitSet(
        String question,
        List<RankedHit> keptHits,
        List<DroppedHit> droppedHits,
        int refineDepth,
        Set<String> instancesUsed,
        Map<String, Double> sourceAffinity,
        List<String> gaps) {

    public RankedHitSet {
        if (question == null) {
            throw new IllegalArgumentException("question is required");
        }
        keptHits = keptHits == null ? List.of() : List.copyOf(keptHits);
        droppedHits = droppedHits == null ? List.of() : List.copyOf(droppedHits);
        instancesUsed = instancesUsed == null ? Set.of() : Set.copyOf(instancesUsed);
        sourceAffinity = sourceAffinity == null ? Map.of() : Map.copyOf(sourceAffinity);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
