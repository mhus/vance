package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: data} document bodies.
 * JSON and YAML only; markdown is intentionally not supported.
 *
 * <p>Unlike the other kind-codecs, the body shape is unconstrained:
 * a top-level object, array, or scalar is all valid. Object form is
 * the canonical recommendation because it's the only one that has
 * room for the {@code $meta} wrapper — non-object bodies have no
 * place to carry {@code kind} on disk, so the codec stamps
 * {@code kind = "data"} on read but the server-side
 * {@code HeaderStrategy} pipeline won't see it.
 *
 * <p>Spec: {@code specification/doc-kind-data.md}.
 */
public final class DataCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Object> JSON_ANY = new TypeReference<>() {};

    private DataCodec() {
        // utility class
    }

    public static DataDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for data: " + mimeType);
    }

    public static String serialize(DataDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for data: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ───────────────────────────────────────────────────────

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }
    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── JSON ───────────────────────────────────────────────────────

    private static DataDocument parseJson(String body) {
        if (body.isBlank()) return DataDocument.empty();
        Object parsed;
        try {
            parsed = JSON.readValue(body, JSON_ANY);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        return promoteFromObject(parsed);
    }

    private static String serializeJson(DataDocument doc) {
        Object onDisk = buildOnDiskValue(doc);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(onDisk) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ── YAML ───────────────────────────────────────────────────────

    private static DataDocument parseYaml(String body) {
        if (body.isBlank()) return DataDocument.empty();
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        List<Object> docs = new ArrayList<>();
        try {
            for (Object d : yaml.loadAll(body)) docs.add(d);
        } catch (RuntimeException e) {
            throw new KindCodecException("Invalid YAML: " + e.getMessage(), e);
        }
        if (docs.isEmpty()) return DataDocument.empty();

        // Multi-doc: doc 1 is the header (must contain `kind`), doc 2
        // is the body. Doc 2 can be Object, Array, or scalar.
        if (docs.size() >= 2 && docs.get(0) instanceof Map<?, ?> header
                && header.containsKey("kind")) {
            Map<String, Object> meta = new LinkedHashMap<>();
            String kind = "";
            for (Map.Entry<?, ?> e : header.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("kind".equals(key)) {
                    kind = (e.getValue() instanceof String ks) ? ks : "";
                } else {
                    meta.put(key, e.getValue());
                }
            }
            return new DataDocument(kind.isEmpty() ? "data" : kind, docs.get(1), meta);
        }

        // Single-doc: same logic as JSON top-level — Object form
        // pulls kind from `kind` (legacy) since there's no $meta in
        // YAML's flat top-level convention; Array/Scalar form just
        // takes the value as body.
        return promoteFromObject(docs.get(0));
    }

    private static String serializeYaml(DataDocument doc) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(100);
        opts.setSplitLines(false);
        Yaml yaml = new Yaml(new Representer(opts), opts);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", canonicalKind(doc));
        for (Map.Entry<String, Object> e : doc.meta().entrySet()) {
            if ("kind".equals(e.getKey())) continue;
            header.put(e.getKey(), e.getValue());
        }
        return yaml.dump(header) + "---\n" + yaml.dump(doc.body());
    }

    // ── Promotion / on-disk ─────────────────────────────────────────

    /**
     * Promote a top-level parsed JSON/YAML value (Object / Array /
     * scalar) into a {@link DataDocument}. Object form goes through
     * {@link KindHeaderCodec#unwrapJsonMeta} so {@code $meta.kind}
     * wins; legacy top-level {@code kind} is also picked up.
     */
    @SuppressWarnings("unchecked")
    private static DataDocument promoteFromObject(@Nullable Object parsed) {
        if (parsed instanceof Map<?, ?> m) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String key) map.put(key, e.getValue());
            }
            map = KindHeaderCodec.unwrapJsonMeta(map);
            Object kindRaw = map.remove("kind");
            String kind = (kindRaw instanceof String s) ? s : "";
            // Anything left in `map` is the body. Pull any remaining
            // header-like passthrough into `meta`? For data we don't
            // distinguish — non-kind header keys end up as ordinary
            // body keys (they were on disk at the same level after
            // unwrap).
            return new DataDocument(kind.isEmpty() ? "data" : kind, map, new LinkedHashMap<>());
        }
        // Array or scalar: kind defaults to "data", body is the
        // value verbatim, meta empty.
        return new DataDocument("data", parsed == null ? new LinkedHashMap<>() : parsed,
                new LinkedHashMap<>());
    }

    /**
     * Build the JSON-friendly on-disk value. For Object bodies we
     * wrap with {@code $meta}; for Array / scalar bodies we emit
     * the body verbatim — there's no place for {@code $meta}, so
     * such documents won't get their {@code kind} mirrored by
     * {@code JsonHeaderStrategy}. The spec recommends Object form
     * for that reason.
     */
    @SuppressWarnings("unchecked")
    private static Object buildOnDiskValue(DataDocument doc) {
        if (doc.body() instanceof Map<?, ?> bm) {
            Map<String, Object> body = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : bm.entrySet()) {
                if (e.getKey() instanceof String key) body.put(key, e.getValue());
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", canonicalKind(doc));
            for (Map.Entry<String, Object> e : doc.meta().entrySet()) {
                if (!"kind".equals(e.getKey())) meta.put(e.getKey(), e.getValue());
            }
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(KindHeaderCodec.META_KEY, meta);
            wrapped.putAll(body);
            return wrapped;
        }
        return doc.body();
    }

    private static String canonicalKind(DataDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "data" : doc.kind();
    }
}
