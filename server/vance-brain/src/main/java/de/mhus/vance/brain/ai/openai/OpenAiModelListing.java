package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import java.util.List;
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
 * <p>Only observations are extracted. {@code kind}, pricing and
 * capabilities stay absent — see {@link DiscoveredModelInfo}'s class
 * doc: pricing belongs to a different source (the vendor's price
 * sheet, operator-owned manual layer), and kind/capabilities are
 * classifications. Pricing blocks some gateways ship in the listing
 * response (cortecs EUR/MTok, OpenRouter per-token USD) are ignored
 * for exactly that reason.
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
        // Pricing blocks some gateways ship (cortecs EUR/MTok, OpenRouter
        // per-token USD) are deliberately ignored: prices are owned by a
        // different source — the operator-managed manual layer.
        return new DiscoveredModelInfo(id, context, output, ownedBy);
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
