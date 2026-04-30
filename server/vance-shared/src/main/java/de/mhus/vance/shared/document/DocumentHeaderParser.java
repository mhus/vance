package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Tolerant front-matter parser for markdown documents. Recognises a YAML-style
 * fenced block at the top of the body:
 *
 * <pre>
 * ---
 * kind: list
 * schema: requirement
 * ---
 * actual content...
 * </pre>
 *
 * <p>Only flat {@code key: value} lines are supported — no nesting, no list
 * syntax, no quoted multi-line strings. Anything that does not match
 * {@code <key>: <value>} (after trimming) inside the fence is silently
 * ignored. Lines starting with {@code #} are treated as comments. Missing or
 * unterminated fence → {@link Optional#empty()}.
 *
 * <p>Header keys are normalised before storage: dots are replaced with
 * underscores so MongoDB does not interpret them as sub-document paths.
 * Whitespace is trimmed; an empty key drops the line.
 */
@Service
public class DocumentHeaderParser {

    private static final String FENCE = "---";

    /**
     * Parses the front matter of {@code body}. Returns {@link Optional#empty()}
     * when the body has no recognisable fence — never throws.
     */
    public Optional<DocumentHeader> parse(@Nullable String body) {
        if (body == null || body.isEmpty()) return Optional.empty();
        String[] lines = body.split("\\R", -1);
        if (lines.length < 2) return Optional.empty();
        if (!FENCE.equals(lines[0].trim())) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        boolean closed = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (FENCE.equals(trimmed)) {
                closed = true;
                break;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String rawKey = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            String key = normalizeKey(rawKey);
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }

        if (!closed) return Optional.empty();
        if (values.isEmpty() && kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    /**
     * Normalise a header key for MongoDB persistence: dots become underscores
     * (Mongo treats dots as path separators), leading {@code $} is dropped
     * (operator prefix), and the result is lower-cased to keep lookups stable
     * across casing differences in the source markdown.
     */
    static String normalizeKey(String rawKey) {
        String key = rawKey.trim();
        if (key.startsWith("$")) key = key.substring(1);
        key = key.replace('.', '_');
        return key.toLowerCase();
    }
}
