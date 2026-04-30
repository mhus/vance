package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * YAML front-matter via the YAML spec's native multi-document feature. A
 * stream of two documents separated by {@code ---} is valid YAML; the first
 * document acts as the header, the second as the body:
 *
 * <pre>
 * kind: list
 * schema: requirement
 * ---
 * - item one
 * - item two
 * </pre>
 *
 * <p>The header is only accepted when <em>all</em> of these hold:
 * <ul>
 *   <li>the first document is a flat {@code Map<String,Object>}</li>
 *   <li>a second document follows (single-doc files are treated as body)</li>
 *   <li>the first document carries a non-blank {@code kind:} entry</li>
 * </ul>
 * The {@code kind} requirement is what discriminates a Vance-typed document
 * from an ordinary multi-document YAML stream (e.g. a Kubernetes manifest):
 * unlike markdown's fence or JSON's {@code $meta} object, multi-document
 * YAML is a legitimate native format on its own and would otherwise yield
 * false-positive headers. No {@code kind} → no header.
 *
 * <p>Map values that are themselves complex (nested map, list) are skipped
 * — only scalar leaves end up in the {@link DocumentHeader#getValues}.
 */
@Component
public class YamlHeaderStrategy implements HeaderStrategy {

    @Override
    public boolean supports(@Nullable String mimeType) {
        String mt = DocumentHeaderParser.canonicalMime(mimeType);
        return "application/yaml".equals(mt)
                || "application/x-yaml".equals(mt)
                || "text/yaml".equals(mt)
                || "text/x-yaml".equals(mt);
    }

    @Override
    public Optional<DocumentHeader> parse(String body) {
        // SafeConstructor so we never instantiate arbitrary classes via
        // YAML's tag system. Document-count cap defends against pathological
        // inputs (a million empty fences would otherwise stream forever).
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        Iterable<Object> docs;
        try {
            docs = yaml.loadAll(body);
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        Object first = null;
        boolean hasSecond = false;
        try {
            int seen = 0;
            for (Object doc : docs) {
                if (seen == 0) {
                    first = doc;
                } else {
                    hasSecond = true;
                    break;
                }
                seen++;
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        if (!hasSecond) return Optional.empty();
        if (!(first instanceof Map<?, ?> rawMap)) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String rawKey)) continue;
            Object rawValue = entry.getValue();
            String value = scalarToString(rawValue);
            if (value == null) continue;
            String key = DocumentHeaderParser.normalizeKey(rawKey);
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }

        if (kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    /**
     * Coerce a YAML scalar leaf to its string form. Returns {@code null} for
     * complex values (nested map, list) so the caller drops the entry —
     * the header projection is intentionally flat.
     */
    private static @Nullable String scalarToString(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return null;
    }
}
