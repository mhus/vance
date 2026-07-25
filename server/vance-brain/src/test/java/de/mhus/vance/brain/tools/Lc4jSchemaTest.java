package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Lc4jSchema} converts the flat map-schema used by
 * {@code Tool.paramsSchema()} into langchain4j's typed schema tree. The
 * converter is deliberately <em>resilient</em>: an under-specified or
 * loosely-worded declaration (a skill-script {@code params:} entry that
 * only says {@code type: array}, a mis-spelled type, …) must never crash
 * the turn, because the schema is only a hint and the receiving tool
 * validates at runtime. These tests pin that resilience so a stricter
 * regression doesn't creep back in.
 */
class Lc4jSchemaTest {

    private static JsonSchemaElement propertyOf(Map<String, Object> propSchema) {
        JsonObjectSchema out = Lc4jSchema.toObjectSchema(Map.of(
                "type", "object",
                "properties", Map.of("p", propSchema)));
        return out.properties().get("p");
    }

    @Test
    void arrayWithoutItems_defaultsToStringElement_insteadOfThrowing() {
        JsonSchemaElement el = propertyOf(Map.of("type", "array"));

        assertThat(el).isInstanceOf(JsonArraySchema.class);
        assertThat(((JsonArraySchema) el).items()).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void arrayWithExplicitItems_preservesDeclaredElement() {
        JsonSchemaElement el = propertyOf(Map.of(
                "type", "array",
                "items", Map.of("type", "integer")));

        assertThat(el).isInstanceOf(JsonArraySchema.class);
        assertThat(((JsonArraySchema) el).items()).isInstanceOf(JsonIntegerSchema.class);
    }

    @Test
    void unknownType_degradesToStringElement_insteadOfThrowing() {
        assertThatCode(() -> propertyOf(Map.of("type", "wibble")))
                .doesNotThrowAnyException();
        assertThat(propertyOf(Map.of("type", "wibble")))
                .isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void missingType_defaultsToString() {
        assertThat(propertyOf(Map.of("description", "no type here")))
                .isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void commonSynonyms_foldToCanonicalShapes() {
        assertThat(propertyOf(Map.of("type", "list")))
                .isInstanceOf(JsonArraySchema.class);
        assertThat(propertyOf(Map.of("type", "bool")))
                .isInstanceOf(JsonBooleanSchema.class);
        assertThat(propertyOf(Map.of("type", "int")))
                .isInstanceOf(JsonIntegerSchema.class);
        assertThat(propertyOf(Map.of("type", "map")))
                .isInstanceOf(JsonObjectSchema.class);
    }

    @Test
    void objectWithoutProperties_isAcceptedAsAnyObject() {
        assertThatCode(() -> propertyOf(Map.of("type", "object")))
                .doesNotThrowAnyException();
        assertThat(propertyOf(Map.of("type", "object")))
                .isInstanceOf(JsonObjectSchema.class);
    }
}
