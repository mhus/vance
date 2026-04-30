package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Markdown front-matter — the YAML-style fenced block at the top of the body:
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
 * syntax, no quoted multi-line strings. Lines starting with {@code #} are
 * treated as comments. A missing or unterminated fence yields no header.
 */
@Component
public class MarkdownHeaderStrategy implements HeaderStrategy {

    private static final String FENCE = "---";

    @Override
    public boolean supports(@Nullable String mimeType) {
        String mt = DocumentHeaderParser.canonicalMime(mimeType);
        return "text/markdown".equals(mt) || "text/x-markdown".equals(mt);
    }

    @Override
    public Optional<DocumentHeader> parse(String body) {
        String[] lines = body.split("\\R", -1);
        if (lines.length < 2) return Optional.empty();
        if (!FENCE.equals(lines[0].trim())) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        boolean closed = false;
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (FENCE.equals(trimmed)) {
                closed = true;
                break;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String rawKey = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            String key = DocumentHeaderParser.normalizeKey(rawKey);
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
}
