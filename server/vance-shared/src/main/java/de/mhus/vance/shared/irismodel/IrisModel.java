package de.mhus.vance.shared.irismodel;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One alias of the Iris catalog.
 *
 * @param id             the alias, the name a client sends as {@code model}
 * @param kind           chat, embedding, image, tts, stt, music, au or decision
 * @param size           SMALL/MEDIUM/LARGE or null
 * @param unitCosts      per-unit prices under the model catalog's own field names
 * @param currency       currency of every price of this alias, or null
 */
public record IrisModel(
        String id,
        @Nullable Integer contextWindowTokens,
        @Nullable Integer maxOutputTokens,
        @Nullable String ownedBy,
        String kind,
        @Nullable String size,
        List<String> capabilities,
        @Nullable TokenPricing pricing,
        Map<String, Object> unitCosts,
        @Nullable String currency) {

    public IrisModel {
        if (id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        capabilities = List.copyOf(capabilities);
        unitCosts = Map.copyOf(unitCosts);
    }

    /** Token prices per million tokens. */
    public record TokenPricing(
            String currency,
            double inputPerMTok,
            double outputPerMTok,
            @Nullable Double cacheReadPerMTok,
            @Nullable Double cacheWritePerMTok) {}
}
