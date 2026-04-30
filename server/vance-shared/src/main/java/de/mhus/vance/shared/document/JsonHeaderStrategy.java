package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
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
}
