package de.mhus.vance.brain.ai;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What an endpoint states about a model's <em>class</em>, as opposed to what it
 * merely observes (wire name, limits): its kind, size, capabilities and per-unit
 * prices.
 *
 * <p>The generic listing parsers never fill this in. For a gateway like the
 * OpenAI wire's, the class of a model is a guess, and an auto-discovered guess
 * outranks the bundled catalog (a Gemini image model listed as chat once
 * dropped out of every image picker). Only an adapter whose endpoint is the
 * <em>owner</em> of its model list — {@code iris} — sets it. Discovery writes it
 * to the machine-owned manual document ({@code auto: true}), never to the
 * auto-document, so an operator keeps the last word by removing the marker.
 *
 * @param kind        catalog kind: chat, embedding, image, tts, stt, music, au, decision
 * @param size        {@code SMALL} or {@code LARGE}, if the endpoint says
 * @param capabilities catalog capability names, may be empty
 * @param costFields  per-unit cost fields under the catalog's own names
 *                    ({@code costPerImage}, {@code costPerChar}, {@code costPerSecond},
 *                    {@code costPerTrack}), already in {@code costCurrency}
 * @param costCurrency currency of {@code costFields} (and of the booked calls), if known
 */
public record ModelClassification(
        String kind,
        @Nullable String size,
        List<String> capabilities,
        Map<String, Object> costFields,
        @Nullable String costCurrency) {

    public ModelClassification {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        capabilities = List.copyOf(capabilities);
        costFields = Map.copyOf(costFields);
    }
}
