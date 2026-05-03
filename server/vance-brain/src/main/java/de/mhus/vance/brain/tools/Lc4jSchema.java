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
 * <p>Silent best-effort is avoided: unknown shapes throw so the tool
 * author notices immediately rather than shipping a broken schema to
 * the LLM.
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
        String type = (String) prop.getOrDefault("type", "string");
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
            case "string" -> desc == null
                    ? new JsonStringSchema()
                    : JsonStringSchema.builder().description(desc).build();
            case "object" -> toObjectSchema(prop);
            case "array" -> buildArray(prop);
            default -> throw new IllegalArgumentException(
                    "Unsupported schema type in tool param: '" + type + "'. "
                            + "Extend Lc4jSchema when adding richer shapes.");
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
        if (!(items instanceof Map<?, ?> itemsMap)) {
            throw new IllegalArgumentException(
                    "Array schema missing 'items' object. Provide the element "
                            + "shape, e.g. items: {\"type\":\"string\"}.");
        }
        b.items(toElement((Map<String, Object>) itemsMap));
        return b.build();
    }
}
