package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Shared helpers for the JSON {@code $meta} wrapper and the YAML
 * multi-document header conventions. Every kind-codec uses these so
 * the on-disk shape stays consistent with the server-side
 * {@link de.mhus.vance.shared.document.JsonHeaderStrategy} and
 * {@link de.mhus.vance.shared.document.YamlHeaderStrategy} — those
 * strategies discover {@code kind:} only in the canonical
 * {@code $meta} / multi-doc forms; without these helpers the
 * codec would write bodies the strategies can't pick up.
 *
 * <p>The read-side helpers are tolerant: legacy single-doc YAML and
 * top-level {@code kind} JSON keep working, the codec normalises on
 * write so a single resave is the migration step.
 *
 * <p>Mirror of the TypeScript {@code documentHeaderCodec.ts}.
 *
 * <p>Stateless utility — no instance state, no Spring bean.
 */
public final class KindHeaderCodec {

    /** Top-level JSON key reserved for the document header. */
    public static final String META_KEY = "$meta";

    private KindHeaderCodec() {
        // utility class
    }

    // ── JSON ────────────────────────────────────────────────────────

    /**
     * Lift a parsed JSON object out of its {@code $meta} wrapper. If
     * the input has a {@code $meta} object, its scalar entries are
     * merged on top of the body keys, with {@code $meta.kind} winning
     * over a legacy top-level {@code kind}.
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
     * Parse a YAML body that may be in multi-document form (header
     * mapping, then {@code ---}, then body) or in legacy single-doc
     * form (everything in one mapping). Returns a flattened map that
     * the kind-specific {@code promoteTo…Document} can consume
     * directly — header keys are merged with body keys.
     *
     * <p>For multi-document with an array body (e.g.
     * {@code - text: foo\n- text: bar}), the array lands under both
     * {@code items} and {@code nodes} so the kind-specific promote
     * function can pick the key it expects.
     *
     * @throws KindCodecException on YAML parse failure or when the
     *         top-level isn't a mapping at all.
     */
    public static Map<String, Object> mergeYamlMultiDoc(String body) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        List<Object> docs = new java.util.ArrayList<>();
        try {
            for (Object doc : yaml.loadAll(body)) {
                docs.add(doc);
            }
        } catch (RuntimeException e) {
            throw new KindCodecException("Invalid YAML: " + e.getMessage(), e);
        }
        if (docs.isEmpty()) return new LinkedHashMap<>();

        // Multi-document with a header mapping carrying kind, plus a
        // distinct body. Normalise into one merged map so the legacy
        // promote-functions don't need to change shape.
        if (docs.size() >= 2 && docs.get(0) instanceof Map<?, ?> firstMap
                && firstMap.containsKey("kind")) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : firstMap.entrySet()) {
                if (e.getKey() instanceof String k) merged.put(k, e.getValue());
            }
            Object body2 = docs.get(1);
            if (body2 instanceof Map<?, ?> bodyMap) {
                for (Map.Entry<?, ?> e : bodyMap.entrySet()) {
                    if (e.getKey() instanceof String k) merged.put(k, e.getValue());
                }
            } else if (body2 instanceof List<?> bodyList) {
                merged.put("items", bodyList);
                merged.put("nodes", bodyList);
            }
            return merged;
        }
        if (docs.get(0) instanceof Map<?, ?> singleMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : singleMap.entrySet()) {
                if (e.getKey() instanceof String k) out.put(k, e.getValue());
            }
            return out;
        }
        throw new KindCodecException("Top-level YAML must be a mapping");
    }

    /**
     * Emit a YAML multi-document body where doc 1 is the header (with
     * {@code kind} and any other scalar metadata) and doc 2 is the
     * body mapping. Both halves are dumped with the same formatting
     * options so the result is internally consistent.
     */
    public static String dumpYamlMultiDoc(String kind, Map<String, Object> body) {
        return dumpYamlMultiDoc(kind, body, null);
    }

    /**
     * Variant that lets the caller add additional scalar header keys
     * beyond {@code kind} (e.g. a {@code schema:} string for
     * records).
     */
    public static String dumpYamlMultiDoc(
            String kind,
            Map<String, Object> body,
            @Nullable Map<String, Object> headerExtra) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", kind);
        if (headerExtra != null) {
            for (Map.Entry<String, Object> e : headerExtra.entrySet()) {
                if ("kind".equals(e.getKey())) continue;
                if (isScalar(e.getValue())) header.put(e.getKey(), e.getValue());
            }
        }
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(100);
        opts.setSplitLines(false);
        Yaml yaml = new Yaml(new Representer(opts), opts);
        return yaml.dump(header) + "---\n" + yaml.dump(body);
    }

    // ── Internal ────────────────────────────────────────────────────

    private static boolean isScalar(@Nullable Object v) {
        return v == null
                || v instanceof String
                || v instanceof Number
                || v instanceof Boolean;
    }
}
