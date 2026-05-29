package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view onto the {@code config.slideshow} block of an
 * {@link ApplicationDocument} with {@code app: slideshow}.
 *
 * <p>Schema (under {@code config.slideshow}):
 * <pre>
 * slideshow:
 *   order:                    # explicit order — overrides alphabetical scan
 *     - 01-cover.png
 *     - 02-pipeline.jpg
 *   captions:                 # optional per-slide caption (else filename stem)
 *     01-cover.png: "Title slide"
 *     02-pipeline.jpg: "Q3 pipeline state"
 *   autoplaySeconds: 5        # 0/missing = manual navigation
 *   aspectRatio: "16:9"       # optional viewport hint
 *   index:
 *     outputPath: _index.yaml
 * </pre>
 */
public final class SlideshowAppConfig {

    public static final String APP_NAME = "slideshow";

    public record IndexConfig(String outputPath) {
        public static IndexConfig defaults() {
            return new IndexConfig("_index.yaml");
        }
    }

    private final List<String> order;
    private final Map<String, String> captions;
    private final int autoplaySeconds;
    private final @Nullable String aspectRatio;
    private final IndexConfig index;

    private SlideshowAppConfig(List<String> order,
                               Map<String, String> captions,
                               int autoplaySeconds,
                               @Nullable String aspectRatio,
                               IndexConfig index) {
        this.order = order;
        this.captions = captions;
        this.autoplaySeconds = autoplaySeconds;
        this.aspectRatio = aspectRatio;
        this.index = index;
    }

    public List<String> order() { return order; }
    public Map<String, String> captions() { return captions; }
    public int autoplaySeconds() { return autoplaySeconds; }
    public @Nullable String aspectRatio() { return aspectRatio; }
    public IndexConfig index() { return index; }

    public static SlideshowAppConfig from(ApplicationDocument doc) {
        if (!APP_NAME.equalsIgnoreCase(doc.app())) {
            throw new KindCodecException(
                    "ApplicationDocument is app='" + doc.app()
                            + "', cannot reinterpret as slideshow.");
        }
        Object raw = doc.config().get(APP_NAME);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return from(typed);
        }
        return new SlideshowAppConfig(
                new ArrayList<>(), new LinkedHashMap<>(),
                0, null, IndexConfig.defaults());
    }

    public static SlideshowAppConfig from(Map<String, Object> block) {
        List<String> order = readStringList(block.get("order"));
        Map<String, String> captions = readStringMap(block.get("captions"));
        int autoplaySeconds = (block.get("autoplaySeconds") instanceof Number n)
                ? Math.max(0, n.intValue()) : 0;
        String aspectRatio = stringOrNull(block.get("aspectRatio"));
        IndexConfig index = readIndex(block.get("index"));
        return new SlideshowAppConfig(order, captions, autoplaySeconds, aspectRatio, index);
    }

    // ── Readers ───────────────────────────────────────────────────

    private static IndexConfig readIndex(@Nullable Object raw) {
        IndexConfig d = IndexConfig.defaults();
        if (!(raw instanceof Map<?, ?> map)) return d;
        String outputPath = stringOrDefault(map.get("outputPath"), d.outputPath());
        return new IndexConfig(outputPath);
    }

    private static List<String> readStringList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            String s = stringOrNull(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static Map<String, String> readStringMap(@Nullable Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            String v = stringOrNull(e.getValue());
            if (v != null) out.put(key, v);
        }
        return out;
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s;
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String stringOrDefault(@Nullable Object v, String fallback) {
        String s = stringOrNull(v);
        return s != null ? s : fallback;
    }
}
