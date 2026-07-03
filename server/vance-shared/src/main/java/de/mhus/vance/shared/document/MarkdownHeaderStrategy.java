package de.mhus.vance.shared.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Fenced front-matter — the YAML-style fenced block at the top of the body:
 *
 * <pre>
 * ---
 * kind: list
 * schema: requirement
 * ---
 * actual content...
 * </pre>
 *
 * <p>Handles {@code text/markdown} only — a {@code .txt} document is plain
 * text and carries no header. Only flat {@code key: value} lines are supported
 * — no nesting, no list syntax, no quoted multi-line strings. Lines starting
 * with {@code #} are treated as comments. A missing or unterminated fence
 * yields no header.
 *
 * <p>The actual split lives in {@link FrontMatter}; this strategy projects
 * the parsed entries onto the {@link DocumentHeader} (normalised keys + kind).
 */
@Component
public class MarkdownHeaderStrategy implements HeaderStrategy {

    /**
     * Upper bound for the prefix the streaming parser materialises into a
     * string. Markdown front matter is a small fenced YAML block at the very
     * top of the document — the parser stops at the closing {@code ---}, so
     * even a 2 KB header is comfortable within this bound. The cap protects
     * against pathological inputs (unterminated fence in a 100 MB markdown
     * body).
     */
    private static final int STREAM_PREFIX_BYTES = 64 * 1024;

    @Override
    public boolean supports(@Nullable String mimeType) {
        String mt = DocumentHeaderParser.canonicalMime(mimeType);
        return "text/markdown".equals(mt)
                || "text/x-markdown".equals(mt);
    }

    @Override
    public Optional<DocumentHeader> parse(String body) {
        FrontMatter fm = FrontMatter.parse(body);
        if (!fm.hasHeader()) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<String, String> e : fm.entries().entrySet()) {
            String key = DocumentHeaderParser.normalizeKey(e.getKey());
            if (key.isEmpty()) continue;
            String value = e.getValue();
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

    @Override
    public Optional<DocumentHeader> parse(InputStream body) throws IOException {
        byte[] prefix = body.readNBytes(STREAM_PREFIX_BYTES);
        return parse(new String(prefix, StandardCharsets.UTF_8));
    }
}
