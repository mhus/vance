package de.mhus.vance.brain.tools;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.List;
import java.util.Map;

/**
 * Converts the flat {@code Map<String, Object>} JSON-Schema subset used
 * by {@link Tool#paramsSchema()} into langchain4j's {@link
 * JsonSchemaElement} tree. Covers object / string / boolean / integer
 * / number / array plus recursive objects and object-valued properties
 * without declared schema (treated as "any object").
 *
 * <p>Resilient by design: the schema is only a hint to the model and
 * the receiving tool validates its arguments at runtime, so an
 * under-specified or loosely-worded declaration must never crash the
 * turn. Missing/blank types default to {@code string}, common synonyms
 * are folded to the canonical set, an unrecognised type degrades to a
 * {@code string} element, and an {@code array} without an explicit
 * {@code items} shape defaults to string elements — we accept what the
 * author (or model) provides rather than rejecting the whole tool.
 */
public final class Lc4jSchema {

    private Lc4jSchema() {}

    @SuppressWarnings("unchecked")
    public static JsonObjectSchema toObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder out = JsonObjectSchema.builder();
        Object description = schema.get("description");
        if (description instanceof String s && !s.isBlank()) {
            out.description(s);
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = (String) e.getKey();
                Map<String, Object> prop = (Map<String, Object>) e.getValue();
                out.addProperty(name, toElement(prop));
            }
        }
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            out.required(list.stream().map(Object::toString).toList());
        }
        return out.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonSchemaElement toElement(Map<String, Object> prop) {
        String type = normalizeType((String) prop.get("type"));
        String desc = (String) prop.get("description");
        return switch (type) {
            case "boolean" -> desc == null
                    ? new JsonBooleanSchema()
                    : JsonBooleanSchema.builder().description(desc).build();
            case "integer" -> desc == null
                    ? new JsonIntegerSchema()
                    : JsonIntegerSchema.builder().description(desc).build();
            case "number" -> desc == null
                    ? new JsonNumberSchema()
                    : JsonNumberSchema.builder().description(desc).build();
            case "object" -> toObjectSchema(prop);
            case "array" -> buildArray(prop);
            // "string" plus any unrecognised type: resilient fallback to a
            // string element. The tool schema is only a hint to the model
            // and the receiving code validates at runtime, so an unknown or
            // mis-spelled type must NOT crash the turn — we accept it as a
            // scalar rather than rejecting the whole tool.
            default -> desc == null
                    ? new JsonStringSchema()
                    : JsonStringSchema.builder().description(desc).build();
        };
    }

    /**
     * Maps a declared type onto the canonical set this converter handles,
     * folding common synonyms ({@code bool}, {@code int}, {@code list},
     * {@code map}, …) so an author's or model's loose wording still yields
     * the intended shape. A blank/absent type defaults to {@code string};
     * anything unrecognised is returned lower-cased and handled by the
     * resilient {@code string} fallback in {@link #toElement}.
     */
    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return "string";
        return switch (raw.trim().toLowerCase()) {
            case "bool" -> "boolean";
            case "int", "long", "short" -> "integer";
            case "float", "double", "decimal" -> "number";
            case "list", "array[]" -> "array";
            case "map", "dict", "dictionary", "json" -> "object";
            case "text", "str" -> "string";
            default -> raw.trim().toLowerCase();
        };
    }

    @SuppressWarnings("unchecked")
    private static JsonArraySchema buildArray(Map<String, Object> prop) {
        JsonArraySchema.Builder b = JsonArraySchema.builder();
        String desc = (String) prop.get("description");
        if (desc != null && !desc.isBlank()) {
            b.description(desc);
        }
        Object items = prop.get("items");
        if (items instanceof Map<?, ?> itemsMap) {
            b.items(toElement((Map<String, Object>) itemsMap));
        } else {
            // Resilient default: an array declared without an explicit
            // element shape (e.g. a skill-script `params:` entry that only
            // says `type: array`) must NOT crash the turn. The tool schema
            // is only a hint to the model; the receiving code does its own
            // runtime validation. Default to string elements — the most
            // common case and the shape models emit most naturally — so we
            // accept the under-specified declaration instead of rejecting it.
            b.items(new JsonStringSchema());
        }
        return b.build();
    }
}
