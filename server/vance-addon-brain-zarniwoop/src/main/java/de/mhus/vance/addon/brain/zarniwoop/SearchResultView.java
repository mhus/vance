package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The answer to one search.
 *
 * <p>{@code error} being set is not an exception here: the dispatcher answers a
 * "no provider could serve this" with a result rather than a throw, and the
 * surface should say so in the tab instead of showing an error page over the
 * whole app. An empty {@code hits} with no error means the index had nothing —
 * a real answer, and not the same thing.
 *
 * @param droppedCount hits the provider withheld or that were unusable. Nonzero
 *                     with an empty list is meaningful and worth showing.
 */
@GenerateTypeScript("search")
public record SearchResultView(
        String query,
        String modality,
        String tier,
        String providerInstanceId,
        List<SearchHitView> hits,
        int droppedCount,
        @Nullable String note,
        @Nullable String error) {}
