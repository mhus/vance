package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelInfo.Pricing;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Parses one {@code data[]} entry of an OpenAI-wire {@code GET /v1/models}
 * response into a {@link DiscoveredModelInfo}. Shared by the chat-completions
 * and Responses-API providers so they agree on what counts as an observation.
 *
 * <p>OpenAI proper returns little more than {@code {id, object, owned_by}} —
 * no context window or output limit. OpenAI-<em>compatible</em> gateways
 * (cortecs, OpenRouter, vLLM, LM Studio, coding-proxy, …) extend the shape
 * with vendor-specific fields. This parser normalises the common dialects:
 *
 * <ul>
 *   <li>{@code contextWindowTokens} — the input/context limit, read from the
 *       first present of {@code context_window}, {@code context_length},
 *       {@code max_context_length}, {@code max_input}, {@code max_input_tokens},
 *       {@code max_tokens}.</li>
 *   <li>{@code maxOutputTokens} — the output/completion limit, read from the
 *       first present of {@code max_output}, {@code max_output_tokens},
 *       {@code max_completion_tokens}.</li>
 *   <li>{@code ownedBy} — from {@code owned_by}.</li>
 * </ul>
 *
 * <p>Only observations are extracted. {@code kind} and capabilities stay
 * absent — see {@link DiscoveredModelInfo}'s class doc for why the auto
 * layer must not assert classifications. Pricing is an observation here
 * only when the endpoint itself reports it; the unit hangs on the field
 * name: cortecs' {@code input_token}/{@code output_token}/
 * {@code cache_read_cost}/{@code cache_write_cost} are EUR per MTok,
 * OpenRouter's {@code prompt}/{@code completion}/{@code cache_read}/
 * {@code cache_write} are USD per token (converted).
 *
 * <p>Pure static utility — no Spring wiring needed.
 */
public final class OpenAiModelListing {

    /** Candidate JSON keys for the input / context-window limit, most-specific first. */
    private static final List<String> CONTEXT_KEYS = List.of(
            "context_window",
            "context_length",
            "context_size",
            "max_context_length",
            "max_input",
            "max_input_tokens",
            "max_tokens");

    /** Candidate JSON keys for the output / completion limit, most-specific first. */
    private static final List<String> OUTPUT_KEYS = List.of("max_output", "max_output_tokens", "max_completion_tokens");

    /**
     * Parse one {@code data[]} entry. The {@code id} is required; every other
     * field is optional and stays null when the entry does not report it.
     */
    public static DiscoveredModelInfo parse(JsonNode entry) {
        String id = entry.path("id").asText();
        if (id.isBlank()) {
            return null;
        }
        Integer context = firstPositiveInt(entry, CONTEXT_KEYS);
        Integer output = firstPositiveInt(entry, OUTPUT_KEYS);
        String ownedBy = entry.path("owned_by").asText(null);
        if (ownedBy != null && ownedBy.isBlank()) {
            ownedBy = null;
        }
        Pricing pricing = parsePricing(entry.path("pricing"));
        return new DiscoveredModelInfo(id, context, output, ownedBy, pricing);
    }

    /**
     * Parse the {@code pricing} object. Field name decides the unit:
     * cortecs reports per MTok, OpenRouter per token. A pricing block
     * needs both input and output — half blocks are dropped rather than
     * asserted. Cache costs are optional. Zero is a valid price (free
     * models); negative or non-numeric values are ignored.
     */
    private static @Nullable Pricing parsePricing(JsonNode pricing) {
        if (pricing == null || !pricing.isObject()) {
            return null;
        }
        Double input = pricePerMTok(pricing, "input_token", "prompt");
        Double output = pricePerMTok(pricing, "output_token", "completion");
        if (input == null || output == null) {
            return null;
        }
        Double cacheRead = pricePerMTok(pricing, "cache_read_cost", "cache_read");
        Double cacheWrite = pricePerMTok(pricing, "cache_write_cost", "cache_write");
        String currency = pricing.path("currency").asText("");
        if (currency.isBlank()) {
            // OpenRouter (the one dialect without a currency field)
            // documents USD; USD is also the bundled convention.
            currency = "USD";
        }
        return new Pricing(currency, input, output, cacheRead, cacheWrite);
    }

    /**
     * Read one price, first key wins. The first key of each pair is the
     * per-MTok dialect, the second the per-token dialect (converted,
     * rounded to 6 decimals to keep the product of the conversion clean
     * in the YAML). Values arrive as JSON numbers (cortecs) or numeric
     * strings (OpenRouter quotes its prices).
     */
    private static @org.jspecify.annotations.Nullable Double pricePerMTok(
            JsonNode pricing, String perMTokKey, String perTokenKey) {
        Double perMTok = priceValue(pricing.get(perMTokKey));
        if (perMTok != null) {
            return perMTok;
        }
        Double perToken = priceValue(pricing.get(perTokenKey));
        if (perToken != null) {
            return Math.round(perToken * 1_000_000.0 * 1_000_000.0) / 1_000_000.0;
        }
        return null;
    }

    /**
     * One price field as a double: JSON number or numeric string.
     * Negative, infinite or unparseable values are ignored.
     */
    private static @org.jspecify.annotations.Nullable Double priceValue(
            @org.jspecify.annotations.Nullable JsonNode node) {
        if (node == null) {
            return null;
        }
        String text;
        if (node.isNumber()) {
            text = node.asText();
        } else if (node.isTextual()) {
            text = node.asText().trim();
        } else {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value >= 0 && Double.isFinite(value)) {
            return value;
        }
        return null;
    }

    private static @org.jspecify.annotations.Nullable Integer firstPositiveInt(JsonNode entry, List<String> keys) {
        for (String key : keys) {
            JsonNode node = entry.get(key);
            if (node != null && node.canConvertToInt()) {
                int value = node.asInt();
                if (value > 0) {
                    return value;
                }
            }
        }
        return null;
    }

    private OpenAiModelListing() {
        // Static utility — no instances.
    }
}
