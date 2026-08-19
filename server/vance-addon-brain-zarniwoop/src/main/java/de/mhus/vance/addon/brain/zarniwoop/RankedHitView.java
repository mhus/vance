package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One hit the curated pipeline kept, with why it kept it.
 *
 * <p>No {@code body} field, and that is not an omission here: {@code RankedHit}
 * carries no content reference at all — the pipeline builds it from a
 * {@link de.mhus.vance.toolpack.research.SearchHit} and drops the body. Giving
 * the evaluate phase the bodies would probably rank better and would certainly
 * cost more tokens; that is a separate decision from this surface.
 *
 * @param relevanceNote the evaluator's own sentence about this hit — the reason
 *                      a person would trust the ordering.
 */
@GenerateTypeScript("search")
public record RankedHitView(
        String title,
        String url,
        String modality,
        String providerInstanceId,
        double finalScore,
        double relevanceScore,
        @Nullable String snippet,
        @Nullable String relevanceNote,
        Map<String, Object> extras) {}
