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
 * YAML front-matter via a reserved top-level {@code $meta} mapping — the
 * exact same convention as {@link JsonHeaderStrategy}. Keeping JSON and
 * YAML symmetric means a single mental model ({@code $meta.kind} lives
 * at the top of the document, regardless of format) and a single parser
 * pathway:
 *
 * <pre>
 * $meta:
 *   kind: list
 *   schema: requirement
 * items:
 *   - item one
 *   - item two
 * </pre>
 *
 * <p>The header is only accepted when the document's top-level value is
 * a YAML mapping containing a {@code $meta} mapping. Sequences, scalars
 * and mappings without {@code $meta} have no header. Non-scalar values
 * inside {@code $meta} (nested map, list) are skipped — same rule as
 * JSON.
 *
 * <p>Multi-document YAML streams (with {@code ---} separators) are no
 * longer treated as Vance-typed documents — that convention was
 * ambiguous with legitimate uses of the YAML spec's multi-doc feature
 * (e.g. Kubernetes manifests). A document with {@code $meta} at the top
 * is the unambiguous signal.
 */
@Component
public class YamlHeaderStrategy implements HeaderStrategy {

    private static final String META_KEY = "$meta";

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
        // YAML's tag system. Alias cap defends against billion-laughs
        // style pathological inputs.
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        Object root;
        try {
            root = yaml.load(body);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!(root instanceof Map<?, ?> rootMap)) return Optional.empty();
        Object metaVal = rootMap.get(META_KEY);
        if (!(metaVal instanceof Map<?, ?> meta)) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<?, ?> entry : meta.entrySet()) {
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
