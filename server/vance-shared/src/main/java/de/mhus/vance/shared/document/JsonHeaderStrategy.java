package de.mhus.vance.shared.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON front-matter via a reserved top-level {@code "$meta"} object —
 * the convention that lines up with {@code $schema} (JSON Schema) and is
 * unambiguously identifiable inside an otherwise-arbitrary JSON document:
 *
 * <pre>
 * {
 *   "$meta": { "kind": "list", "schema": "requirement" },
 *   "items": [ ... ]
 * }
 * </pre>
 *
 * <p>The header is only accepted when the document's top-level value is a
 * JSON object containing a {@code $meta} object. Arrays, primitives and
 * objects without {@code $meta} have no header. Non-scalar values inside
 * {@code $meta} (nested objects, arrays) are skipped — same rule as YAML.
 */
@Component
public class JsonHeaderStrategy implements HeaderStrategy {

    private static final String META_KEY = "$meta";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonFactory jsonFactory = new JsonFactory();

    @Override
    public boolean supports(@Nullable String mimeType) {
        String mt = DocumentHeaderParser.canonicalMime(mimeType);
        return "application/json".equals(mt) || "text/json".equals(mt);
    }

    @Override
    public Optional<DocumentHeader> parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        JsonNode meta = root.get(META_KEY);
        if (meta == null || !meta.isObject()) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<String, JsonNode> entry : meta.properties()) {
            JsonNode v = entry.getValue();
            String value = scalarToString(v);
            if (value == null) continue;
            String key = DocumentHeaderParser.normalizeKey(entry.getKey());
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }

        if (values.isEmpty() && kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    /**
     * Scalar-only string coercion, mirroring {@link YamlHeaderStrategy}:
     * objects and arrays return {@code null} so the caller drops the entry.
     * {@code null} JSON value maps to the empty string — the key was
     * declared, just without a meaningful value.
     */
    private static @Nullable String scalarToString(JsonNode node) {
        if (node.isNull()) return "";
        if (node.isTextual()) return node.textValue();
        if (node.isNumber() || node.isBoolean()) return node.asString();
        return null;
    }

    /**
     * Streaming variant of {@link #parse(String)} — drives Jackson's pull
     * parser directly over the input stream so we never materialise the body
     * in memory. Algorithm:
     *
     * <ol>
     *   <li>Expect {@code START_OBJECT} at the root; bail out on anything
     *       else.</li>
     *   <li>Walk top-level fields, skipping values until {@code $meta} is
     *       found.</li>
     *   <li>Inside {@code $meta}, read scalar key/value pairs into the
     *       result map; nested objects and arrays are skipped (mirrors the
     *       string-based variant).</li>
     *   <li>Stop reading once {@code $meta} ends — the rest of the body is
     *       irrelevant.</li>
     * </ol>
     *
     * <p>{@code IOException} bubbles up so the caller can distinguish a
     * transport-level error from a JSON parse failure (which still becomes
     * {@link Optional#empty()}).
     */
    @Override
    public Optional<DocumentHeader> parse(InputStream body) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return Optional.empty();
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == null || token == JsonToken.END_OBJECT) break;
                if (token != JsonToken.PROPERTY_NAME) {
                    // Defensive — well-formed JSON only emits PROPERTY_NAME or END_OBJECT here.
                    return Optional.empty();
                }
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (META_KEY.equals(field)) {
                    return readMetaObject(parser, valueToken);
                }
                if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
                    parser.skipChildren();
                }
            }
            return Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private Optional<DocumentHeader> readMetaObject(JsonParser parser, JsonToken openToken)
            throws IOException {
        if (openToken != JsonToken.START_OBJECT) return Optional.empty();
        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == null || token == JsonToken.END_OBJECT) break;
            if (token != JsonToken.PROPERTY_NAME) return Optional.empty();
            String rawKey = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            String value = scalarFromToken(parser, valueToken);
            if (value == null) {
                if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
                    parser.skipChildren();
                }
                continue;
            }
            String key = DocumentHeaderParser.normalizeKey(rawKey);
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }
        if (values.isEmpty() && kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    private static @Nullable String scalarFromToken(JsonParser parser, JsonToken token)
            throws IOException {
        return switch (token) {
            case VALUE_NULL -> "";
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE ->
                    parser.getValueAsString();
            default -> null;
        };
    }
}
