package de.mhus.vance.shared.irismodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the Iris model listing. Iris speaks the OpenAI listing plus what a catalog
 * needs: {@code kind}, {@code size}, {@code capabilities}, {@code pricing} (EUR per
 * million tokens) and {@code unit_pricing} (EUR per unit), mapped here to the field
 * names of the Vance model catalog.
 */
public final class IrisCatalog {

    private static final double CHARS_PER_THOUSAND = 1000.0;
    private static final List<String> CONTEXT_KEYS = List.of("context_window", "context_length");
    private static final List<String> OUTPUT_KEYS = List.of("max_output_tokens", "max_completion_tokens");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IrisCatalog() {}

    /** A parsed listing. {@code version} is Iris' configuration version, or -1. */
    public record Listing(long version, List<IrisModel> models) {}

    /** Parses a whole {@code /v1/models} body. */
    public static Listing parseListing(String body) {
        JsonNode root = MAPPER.readTree(body);
        List<IrisModel> models = new ArrayList<>();
        for (JsonNode entry : root.path("data")) {
            IrisModel m = parseEntry(entry);
            if (m != null) {
                models.add(m);
            }
        }
        return new Listing(root.path("version").asLong(-1), models);
    }

    /** One listing entry; null when it has no id or states no kind. */
    public static @Nullable IrisModel parseEntry(JsonNode entry) {
        String id = entry.path("id").asString("").trim();
        String kind = entry.path("kind").asString("").trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || kind.isEmpty()) {
            return null;
        }
        String size = entry.path("size").asString("").trim().toUpperCase(Locale.ROOT);
        List<String> capabilities = new ArrayList<>();
        for (JsonNode c : entry.path("capabilities")) {
            String name = c.asString("").trim();
            if (!name.isEmpty()) {
                capabilities.add(name);
            }
        }
        String owner = entry.path("owned_by").asString("").trim();
        JsonNode unit = entry.path("unit_pricing");
        IrisModel.TokenPricing pricing = tokenPricing(entry.path("pricing"));
        String currency = firstNonBlank(
                unit.path("currency").asString(""),
                entry.path("pricing").path("currency").asString(""));
        return new IrisModel(
                id,
                firstPositiveInt(entry, CONTEXT_KEYS),
                firstPositiveInt(entry, OUTPUT_KEYS),
                owner.isEmpty() ? null : owner,
                kind,
                size.isEmpty() ? null : size,
                capabilities,
                pricing,
                unitCosts(kind, unit),
                currency);
    }

    private static IrisModel.@Nullable TokenPricing tokenPricing(JsonNode p) {
        if (!p.isObject()) {
            return null;
        }
        Double in = nonNegative(p, "input_token");
        Double out = nonNegative(p, "output_token");
        if (in == null || out == null) {
            return null;
        }
        String currency = p.path("currency").asString("").trim();
        return new IrisModel.TokenPricing(
                currency.isEmpty() ? "EUR" : currency,
                in,
                out,
                positive(p, "cache_read_cost"),
                positive(p, "cache_write_cost"));
    }

    private static Map<String, Object> unitCosts(String kind, JsonNode unit) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case "image" -> {
                Double perImage = positive(unit, "per_image");
                if (perImage != null) {
                    out.put("costPerImage", Map.of("standard", perImage));
                }
            }
            case "tts" -> {
                Double per1k = positive(unit, "per_1k_chars");
                if (per1k != null) {
                    out.put("costPerChar", per1k / CHARS_PER_THOUSAND);
                }
            }
            case "stt" -> putIfPresent(out, "costPerSecond", positive(unit, "per_second"));
            case "music" -> putIfPresent(out, "costPerTrack", positive(unit, "per_track"));
            default -> {
                // chat and embedding are priced through the token block; audio analysis is billed by
                // tokens and a decision by question, neither of which the catalog can state per unit.
            }
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, @Nullable Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    /** A strictly positive number, or null. A price of zero is "not stated" in the catalog. */
    private static @Nullable Double positive(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() > 0 ? v.asDouble() : null;
    }

    /** A price that may legitimately be zero (a free model). */
    private static @Nullable Double nonNegative(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() >= 0 ? v.asDouble() : null;
    }

    private static @Nullable Integer firstPositiveInt(JsonNode entry, List<String> keys) {
        for (String key : keys) {
            JsonNode n = entry.get(key);
            if (n != null && n.canConvertToInt() && n.asInt() > 0) {
                return n.asInt();
            }
        }
        return null;
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (!v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
