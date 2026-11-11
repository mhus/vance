package de.mhus.vance.brain.ai.iris;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelClassification;
import de.mhus.vance.brain.ai.openai.OpenAiModelListing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final double CHARS_PER_THOUSAND = 1000.0;

    private IrisModelListing() {}

    static @Nullable DiscoveredModelInfo parse(JsonNode entry) {
        DiscoveredModelInfo base = OpenAiModelListing.parse(entry);
        if (base == null) {
            return null;
        }
        String kind = entry.path("kind").asString("").trim().toLowerCase(java.util.Locale.ROOT);
        if (kind.isEmpty()) {
            return base;
        }
        String size = entry.path("size").asString("").trim().toUpperCase(java.util.Locale.ROOT);
        List<String> capabilities = new ArrayList<>();
        for (JsonNode c : entry.path("capabilities")) {
            String name = c.asString("").trim();
            if (!name.isEmpty()) {
                capabilities.add(name);
            }
        }
        JsonNode unit = entry.path("unit_pricing");
        String currency = firstNonBlank(
                unit.path("currency").asString(""),
                entry.path("pricing").path("currency").asString(""));
        return new DiscoveredModelInfo(
                base.wireName(),
                base.contextWindowTokens(),
                base.maxOutputTokens(),
                base.ownedBy(),
                base.pricing(),
                new ModelClassification(
                        kind, size.isEmpty() ? null : size, capabilities, unitCosts(kind, unit), currency));
    }

    /** The per-unit prices under the catalog's own field names. */
    private static Map<String, Object> unitCosts(String kind, JsonNode unit) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case "image" -> {
                Double perImage = number(unit, "per_image");
                if (perImage != null) {
                    out.put("costPerImage", Map.of("standard", perImage));
                }
            }
            case "tts" -> {
                Double per1k = number(unit, "per_1k_chars");
                if (per1k != null) {
                    out.put("costPerChar", per1k / CHARS_PER_THOUSAND);
                }
            }
            case "stt" -> put(out, "costPerSecond", number(unit, "per_second"));
            case "music" -> put(out, "costPerTrack", number(unit, "per_track"));
            default -> {
                // chat and embedding are priced through the pricing block; audio analysis is billed
                // by tokens and a decision by question, neither of which the catalog can state.
            }
        }
        return out;
    }

    private static void put(Map<String, Object> out, String key, @Nullable Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static @Nullable Double number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() > 0 ? v.asDouble() : null;
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
