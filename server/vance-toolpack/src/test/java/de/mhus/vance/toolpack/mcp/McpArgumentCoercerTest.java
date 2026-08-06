package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpArgumentCoercerTest {

    private static Map<String, Object> schema(Object... typeByProperty) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < typeByProperty.length; i += 2) {
            Object type = typeByProperty[i + 1];
            properties.put(
                    (String) typeByProperty[i],
                    type instanceof Map<?, ?> m ? m : Map.of("type", type));
        }
        return Map.of("type", "object", "properties", properties);
    }

    @Test
    void stringTrue_onBooleanProperty_becomesBoolean() {
        // The chrome-devtools-mcp / glm-5.2 combination from the field:
        // press_key({key:"Enter", includeSnapshot:"true"}) was rejected
        // with -32602 "expected boolean, received string".
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "Enter");
        args.put("includeSnapshot", "true");

        Map<String, Object> out = McpArgumentCoercer.coerce(
                args, schema("key", "string", "includeSnapshot", "boolean"));

        assertThat(out).containsEntry("includeSnapshot", Boolean.TRUE);
        assertThat(out).containsEntry("key", "Enter");
    }

    @Test
    void stringFalse_onBooleanProperty_becomesBoolean() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("verbose", " FALSE "), schema("verbose", "boolean"));

        assertThat(out).containsEntry("verbose", Boolean.FALSE);
    }

    @Test
    void nonBooleanWord_onBooleanProperty_passesThroughUnchanged() {
        // "yes" is a guess, not a representation — let the server's own
        // validation error reach the model instead of inventing intent.
        Map<String, Object> args = Map.of("verbose", "yes");

        assertThat(McpArgumentCoercer.coerce(args, schema("verbose", "boolean")))
                .isSameAs(args);
    }

    @Test
    void numericString_onIntegerProperty_becomesLong() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("timeout", "5000"), schema("timeout", "integer"));

        assertThat(out).containsEntry("timeout", 5000L);
    }

    @Test
    void integralDouble_onIntegerProperty_losesFraction() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("count", 3.0d), schema("count", "integer"));

        assertThat(out).containsEntry("count", 3L);
    }

    @Test
    void fractionalDouble_onIntegerProperty_passesThroughUnchanged() {
        Map<String, Object> args = Map.of("count", 3.5d);

        assertThat(McpArgumentCoercer.coerce(args, schema("count", "integer")))
                .isSameAs(args);
    }

    @Test
    void numericString_onNumberProperty_becomesNumber() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("ratio", "0.25"), schema("ratio", "number"));

        assertThat(out).containsEntry("ratio", 0.25d);
    }

    @Test
    void number_onStringProperty_becomesString() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("uid", 15L), schema("uid", "string"));

        assertThat(out).containsEntry("uid", "15");
    }

    @Test
    void jsonText_onArrayProperty_becomesListWithCoercedItems() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("pages", "[\"1\", \"2\"]"),
                schema("pages", Map.of("type", "array", "items", Map.of("type", "integer"))));

        assertThat(out).containsEntry("pages", List.of(1L, 2L));
    }

    @Test
    void jsonText_onObjectProperty_becomesMapWithCoercedProperties() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("options", "{\"deep\": \"true\"}"),
                schema("options", Map.of(
                        "type", "object",
                        "properties", Map.of("deep", Map.of("type", "boolean")))));

        assertThat(out).containsEntry("options", Map.of("deep", Boolean.TRUE));
    }

    @Test
    void nestedObject_coercesInPlace() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("deep", "false");

        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("options", nested),
                schema("options", Map.of(
                        "type", "object",
                        "properties", Map.of("deep", Map.of("type", "boolean")))));

        assertThat(out).containsEntry("options", Map.of("deep", Boolean.FALSE));
    }

    @Test
    void nonJsonText_onArrayProperty_passesThroughUnchanged() {
        Map<String, Object> args = Map.of("pages", "first, second");

        assertThat(McpArgumentCoercer.coerce(
                        args, schema("pages", Map.of("type", "array", "items", Map.of("type", "string")))))
                .isSameAs(args);
    }

    @Test
    void nullableUnionType_coercesTowardsTheSingleRealType() {
        Map<String, Object> out = McpArgumentCoercer.coerce(
                Map.of("verbose", "true"),
                schema("verbose", Map.of("type", List.of("boolean", "null"))));

        assertThat(out).containsEntry("verbose", Boolean.TRUE);
    }

    @Test
    void genuineUnionType_passesThroughUnchanged() {
        // No unambiguous target — coercing either way would be a guess.
        Map<String, Object> args = Map.of("value", "true");

        assertThat(McpArgumentCoercer.coerce(
                        args, schema("value", Map.of("type", List.of("boolean", "string")))))
                .isSameAs(args);
    }

    @Test
    void undeclaredKey_passesThroughUnchanged() {
        // MCP schemas commonly set additionalProperties:true; an unknown
        // key has no declared type to coerce towards.
        Map<String, Object> args = Map.of("extra", "true");

        assertThat(McpArgumentCoercer.coerce(args, schema("known", "boolean")))
                .isSameAs(args);
    }

    @Test
    void alreadyTypedArguments_returnTheSameInstance() {
        Map<String, Object> args = Map.of("key", "Enter", "includeSnapshot", Boolean.TRUE);

        assertThat(McpArgumentCoercer.coerce(
                        args, schema("key", "string", "includeSnapshot", "boolean")))
                .isSameAs(args);
    }

    @Test
    void nullValue_isKeptAsNull() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("verbose", null);

        Map<String, Object> out = McpArgumentCoercer.coerce(args, schema("verbose", "boolean"));

        assertThat(out).containsEntry("verbose", null);
    }

    @Test
    void missingSchema_isTolerated() {
        Map<String, Object> args = Map.of("verbose", "true");

        assertThat(McpArgumentCoercer.coerce(args, null)).isSameAs(args);
        assertThat(McpArgumentCoercer.coerce(args, Map.of("type", "object"))).isSameAs(args);
    }
}
