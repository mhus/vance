package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Shared helpers for the {@code $meta} wrapper convention used by every
 * kind-codec for both JSON and YAML. The on-disk shape is symmetric:
 *
 * <pre>
 * JSON:                                YAML:
 * {                                    $meta:
 *   "$meta": { "kind": "list" },         kind: list
 *   "items": [...]                     items: [...]
 * }
 * </pre>
 *
 * Every kind-codec uses these so the on-disk shape stays consistent
 * with the server-side
 * {@link de.mhus.vance.shared.document.JsonHeaderStrategy} and
 * {@link de.mhus.vance.shared.document.YamlHeaderStrategy} — both
 * discover {@code kind:} only in the canonical {@code $meta} form;
 * without these helpers the codec would write bodies the strategies
 * can't pick up.
 *
 * <p>Mirror of the TypeScript {@code documentHeaderCodec.ts}.
 *
 * <p>Stateless utility — no instance state, no Spring bean.
 */
public final class KindHeaderCodec {

    /** Top-level key reserved for the document header. */
    public static final String META_KEY = "$meta";

    private KindHeaderCodec() {
        // utility class
    }

    // ── JSON ────────────────────────────────────────────────────────

    /**
     * Lift a parsed JSON object out of its {@code $meta} wrapper. If
     * the input has a {@code $meta} object, its scalar entries are
     * merged on top of the body keys.
     *
     * <p>Non-scalar {@code $meta} values are dropped — they wouldn't
     * survive a round-trip through {@code JsonHeaderStrategy} either,
     * which requires scalars in the header object.
     *
     * @param obj parsed JSON object (e.g. result of
     *            {@code ObjectMapper.readValue(body, Map.class)}).
     * @return a flattened map with {@code kind} (and any other scalar
     *         {@code $meta} keys) at the top level next to the body
     *         keys.
     */
    public static Map<String, Object> unwrapJsonMeta(Map<String, Object> obj) {
        Object metaVal = obj.get(META_KEY);
        if (!(metaVal instanceof Map<?, ?> meta)) return obj;
        Map<String, Object> merged = new LinkedHashMap<>(obj);
        merged.remove(META_KEY);
        for (Map.Entry<?, ?> e : meta.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            Object v = e.getValue();
            if (isScalar(v)) merged.put(key, v);
        }
        return merged;
    }

    /**
     * Build a JSON object with {@code $meta} wrapping {@code kind}.
     * Body keys live at the top level alongside {@code $meta} so the
     * server's {@code JsonHeaderStrategy} can find {@code kind} and
     * the editor can still read the typed body keys directly.
     */
    public static Map<String, Object> wrapJsonMeta(String kind, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", kind);
        out.put(META_KEY, meta);
        out.putAll(body);
        return out;
    }

    // ── YAML ────────────────────────────────────────────────────────

    /**
     * Parse a single-document YAML body and unwrap its {@code $meta}
     * mapping. Returns a flattened map that the kind-specific
     * {@code promoteTo…Document} can consume directly — scalar
     * {@code $meta} entries (kind, schema, …) land at the top level
     * next to the body keys.
     *
     * <p>The top-level YAML value must be a mapping; anything else
     * (sequence, scalar, empty stream) raises {@link KindCodecException}.
     * Documents without a {@code $meta} mapping pass through unchanged
     * — the codec can still read body keys, the server-side
     * {@code YamlHeaderStrategy} just won't mirror {@code kind} on
     * save.
     *
     * @throws KindCodecException on YAML parse failure or when the
     *         top-level isn't a mapping at all.
     */
    public static Map<String, Object> parseYamlBody(String body) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        Object root;
        try {
            root = yaml.load(body);
        } catch (RuntimeException e) {
            throw new KindCodecException("Invalid YAML: " + e.getMessage(), e);
        }
        if (root == null) return new LinkedHashMap<>();
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new KindCodecException("Top-level YAML must be a mapping");
        }
        Map<String, Object> top = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rootMap.entrySet()) {
            if (e.getKey() instanceof String k) top.put(k, e.getValue());
        }
        return unwrapJsonMeta(top);
    }

    /**
     * Emit a single-document YAML body with {@code $meta} as the first
     * top-level key carrying {@code kind} (plus any optional scalar
     * extras from {@code headerExtra}). The shape mirrors the JSON
     * {@code wrapJsonMeta} output exactly — a {@code $meta} mapping
     * followed by the body keys at the same level.
     */
    public static String dumpYamlBody(String kind, Map<String, Object> body) {
        return dumpYamlBody(kind, body, null);
    }

    /**
     * Variant that lets the caller add additional scalar header keys
     * beyond {@code kind} (e.g. a {@code schema:} string for records).
     */
    public static String dumpYamlBody(
            String kind,
            Map<String, Object> body,
            @Nullable Map<String, Object> headerExtra) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", kind);
        if (headerExtra != null) {
            for (Map.Entry<String, Object> e : headerExtra.entrySet()) {
                if ("kind".equals(e.getKey())) continue;
                if (isScalar(e.getValue())) meta.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(META_KEY, meta);
        wrapped.putAll(body);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(100);
        opts.setSplitLines(false);
        Yaml yaml = new Yaml(new Representer(opts), opts);
        return yaml.dump(wrapped);
    }

    // ── Internal ────────────────────────────────────────────────────

    private static boolean isScalar(@Nullable Object v) {
        return v == null
                || v instanceof String
                || v instanceof Number
                || v instanceof Boolean;
    }
}
