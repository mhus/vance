package de.mhus.vance.brain.ai.iris;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelClassification;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.openai.OpenAiModelListing;
import de.mhus.vance.shared.irismodel.IrisCatalog;
import de.mhus.vance.shared.irismodel.IrisModel;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads one entry of Iris' {@code GET /v1/models}.
 *
 * <p>Iris' listing is the OpenAI listing plus what a catalog needs: the observations
 * (id, {@code context_window}, {@code max_output_tokens}) and the token prices
 * ({@code pricing}, EUR per million tokens) use the key names the gateway parser
 * {@link OpenAiModelListing} already reads. This class adds what Iris, and only
 * Iris, may state about its own models: {@code kind}, {@code size},
 * {@code capabilities} and the per-unit prices ({@code unit_pricing}, EUR) mapped
 * to the catalog's field names.
 */
final class IrisModelListing {

    private IrisModelListing() {}

    static @Nullable DiscoveredModelInfo parse(JsonNode entry) {
        IrisModel m = IrisCatalog.parseEntry(entry);
        if (m == null) {
            return OpenAiModelListing.parse(entry);
        }
        IrisModel.TokenPricing p = m.pricing();
        ModelInfo.Pricing pricing = p == null
                ? null
                : new ModelInfo.Pricing(
                        p.currency(), p.inputPerMTok(), p.outputPerMTok(), p.cacheReadPerMTok(), p.cacheWritePerMTok());
        return new DiscoveredModelInfo(
                m.id(),
                m.contextWindowTokens(),
                m.maxOutputTokens(),
                m.ownedBy(),
                pricing,
                new ModelClassification(m.kind(), m.size(), m.capabilities(), m.unitCosts(), m.currency()));
    }
}
