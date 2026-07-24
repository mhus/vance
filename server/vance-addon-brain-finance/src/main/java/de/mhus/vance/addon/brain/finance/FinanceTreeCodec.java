package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.InterestBasis;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: finance-tree} document bodies. YAML
 * is canonical, JSON is a 1:1 dual — both round-trip through the same typed
 * {@link FinanceTreeDocument} model. Markdown is not supported (a computed
 * tree is not prose); the codec throws {@link KindCodecException}.
 *
 * <p>Stateless utility — the {@code nodeFromMap} / {@code nodeToMap} and
 * {@code valueFromMap} / {@code valueToMap} helpers are {@code public static}
 * so the {@code finance_*} tools share one grammar with the on-disk shape.
 *
 * <p>Compactness rules (to keep the on-disk tree lean, symmetric with the
 * defaults re-applied on parse): {@code sign} is emitted only when
 * {@code != +1}; {@code mode} only for {@link ValueMode#ONE_TIME}; empty
 * {@code values}/{@code children} lists are dropped; interest {@code basis}
 * only when {@code != VOM_HUNDERT} and {@code compound} only when {@code true}.
 */
public final class FinanceTreeCodec {

    public static final String KIND = "finance-tree";

    /**
     * Fixed comment header prepended to the YAML body — orients both the agent
     * (visible in {@code doc_read}) and a human reading the raw file. SnakeYAML
     * ignores leading comments on parse, and exactly one line-pair is prepended
     * on every serialize, so the model round-trip and serialize idempotency
     * both hold. Not applied to JSON (no comments).
     */
    private static final String YAML_HINT =
            "# vance finance-tree v1 · amounts are per record; a node's sign flips its whole subtree\n"
            + "# agent: run manual_read('finance-tree') before interpreting · computed values under $computed\n";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private FinanceTreeCodec() {
        // utility class
    }

    // ── Entry points ──────────────────────────────────────────────

    public static FinanceTreeDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseFlat(parseJson(body));
        if (isYaml(mimeType)) return parseFlat(KindHeaderCodec.parseYamlBody(body));
        throw new KindCodecException("Unsupported mime type for finance-tree: " + mimeType);
    }

    public static String serialize(FinanceTreeDocument doc, @Nullable String mimeType) {
        return serialize(doc, null, mimeType);
    }

    /**
     * Serialise with an optional computed overlay written under
     * {@code $computed}. The overlay is derived data — dropped on the next
     * {@link #parse} (parse reads only the input keys), so a recompute simply
     * rewrites it.
     */
    public static String serialize(FinanceTreeDocument doc, @Nullable FinanceComputed computed,
                                   @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc, computed);
        if (isYaml(mimeType)) {
            return YAML_HINT + KindHeaderCodec.dumpYamlBody(KIND, buildBody(doc, computed));
        }
        throw new KindCodecException("Unsupported mime type for finance-tree: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── Parse ─────────────────────────────────────────────────────

    private static Map<String, Object> parseJson(String body) {
        if (body.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return KindHeaderCodec.unwrapJsonMeta(parsed);
    }

    private static FinanceTreeDocument parseFlat(Map<String, Object> top) {
        int version = intOr(top.get("version"), FinanceTreeDocument.CURRENT_VERSION);
        String title = str(top, "title");
        String description = str(top, "description");
        FinanceNode root = top.get("root") == null ? null : nodeFromMap(asMap(top.get("root")));
        return new FinanceTreeDocument(version, title, description, root);
    }

    // ── Serialize ─────────────────────────────────────────────────

    private static String serializeJson(FinanceTreeDocument doc, @Nullable FinanceComputed computed) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(KIND, buildBody(doc, computed));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Map<String, Object> buildBody(FinanceTreeDocument doc,
                                                 @Nullable FinanceComputed computed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", doc.version());
        if (doc.title() != null) body.put("title", doc.title());
        if (doc.description() != null) body.put("description", doc.description());
        if (doc.root() != null) body.put("root", nodeToMap(doc.root()));
        if (computed != null) body.put("$computed", computedToMap(computed));
        return body;
    }

    private static Map<String, Object> computedToMap(FinanceComputed c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.computedAt() != null) m.put("computedAt", c.computedAt());
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (NodeSnapshot s : c.nodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("perYear", round(s.perYear()));
            n.put("perMonth", round(s.perMonth()));
            n.put("perWeek", round(s.perWeek()));
            n.put("perDay", round(s.perDay()));
            n.put("base", round(s.base()));
            n.put("interest", round(s.interest()));
            n.put("oneTimeSum", round(s.oneTimeSum()));
            nodes.put(s.name(), n);
        }
        m.put("nodes", nodes);
        return m;
    }

    /** Trim float noise for display; the canonical value is the raw double. */
    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    // ── Node map ↔ model ──────────────────────────────────────────

    public static FinanceNode nodeFromMap(Map<String, Object> raw) {
        String name = str(raw, "name");
        if (name == null) throw new KindCodecException("finance node requires `name`");
        int sign = intOr(raw.get("sign"), 1) < 0 ? -1 : 1;

        List<FinanceValue> values = new ArrayList<>();
        for (Map<String, Object> v : mapList(raw.get("values"))) {
            values.add(valueFromMap(v));
        }
        List<FinanceNode> children = new ArrayList<>();
        for (Map<String, Object> c : mapList(raw.get("children"))) {
            children.add(nodeFromMap(c));
        }
        return new FinanceNode(
                name,
                str(raw, "title"),
                str(raw, "icon"),
                str(raw, "color"),
                sign,
                str(raw, "description"),
                str(raw, "notesRef"),
                values,
                children);
    }

    public static Map<String, Object> nodeToMap(FinanceNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.name());
        if (n.title() != null) m.put("title", n.title());
        if (n.icon() != null) m.put("icon", n.icon());
        if (n.color() != null) m.put("color", n.color());
        if (n.sign() != 1) m.put("sign", n.sign());
        if (n.description() != null) m.put("description", n.description());
        if (n.notesRef() != null) m.put("notesRef", n.notesRef());
        if (!n.values().isEmpty()) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (FinanceValue v : n.values()) values.add(valueToMap(v));
            m.put("values", values);
        }
        if (!n.children().isEmpty()) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (FinanceNode c : n.children()) children.add(nodeToMap(c));
            m.put("children", children);
        }
        return m;
    }

    // ── Value map ↔ model ─────────────────────────────────────────

    public static FinanceValue valueFromMap(Map<String, Object> raw) {
        Double value = dblOrNull(raw.get("value"));
        if (value == null) throw new KindCodecException("finance value requires `value`");
        ValueMode mode = ValueMode.parse(str(raw, "mode"), ValueMode.RECURRING);
        Period period = periodFromMap(raw.get("period"));
        String validFrom = dateStr(raw.get("validFrom"));
        String validTo = dateStr(raw.get("validTo"));
        Integer sign = intOrNull(raw.get("sign"));
        FinanceInterest interest = interestFromMap(raw.get("interest"));

        if (mode == ValueMode.RECURRING && period == null) {
            throw new KindCodecException("recurring finance value requires `period`");
        }
        if (mode == ValueMode.ONE_TIME && validFrom == null) {
            throw new KindCodecException("one_time finance value requires `validFrom` (the date)");
        }
        return new FinanceValue(value, mode, period, validFrom, validTo, sign, interest);
    }

    public static Map<String, Object> valueToMap(FinanceValue v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v.value());
        if (v.mode() != ValueMode.RECURRING) m.put("mode", v.mode().wire());
        if (v.period() != null) m.put("period", periodToMap(v.period()));
        if (v.validFrom() != null) m.put("validFrom", v.validFrom());
        if (v.validTo() != null) m.put("validTo", v.validTo());
        if (v.sign() != null) m.put("sign", v.sign());
        if (v.interest() != null) m.put("interest", interestToMap(v.interest()));
        return m;
    }

    // ── Period map ↔ model ────────────────────────────────────────

    private static @Nullable Period periodFromMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> m = asMap(raw);
        int count = intOr(m.get("count"), 1);
        PeriodUnit unit = PeriodUnit.parse(str(m, "unit"));
        if (unit == null) throw new KindCodecException("finance period requires a valid `unit`");
        return new Period(count, unit);
    }

    private static Map<String, Object> periodToMap(Period p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", p.count());
        m.put("unit", p.unit().wire());
        return m;
    }

    // ── Interest map ↔ model ──────────────────────────────────────

    private static @Nullable FinanceInterest interestFromMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> m = asMap(raw);
        Double rate = dblOrNull(m.get("rate"));
        if (rate == null) throw new KindCodecException("finance interest requires `rate`");
        Period period = periodFromMap(m.get("period"));
        if (period == null) period = new Period(1, PeriodUnit.YEAR);
        InterestBasis basis = InterestBasis.parse(str(m, "basis"), InterestBasis.VOM_HUNDERT);
        boolean compound = boolOr(m.get("compound"), false);
        return new FinanceInterest(rate, period, basis, compound);
    }

    private static Map<String, Object> interestToMap(FinanceInterest i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", i.rate());
        m.put("period", periodToMap(i.period()));
        if (i.basis() != InterestBasis.VOM_HUNDERT) m.put("basis", i.basis().wire());
        if (i.compound()) m.put("compound", true);
        return m;
    }

    // ── Coercion helpers ──────────────────────────────────────────

    private static @Nullable String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    /**
     * ISO {@code yyyy-MM-dd} date string. SnakeYAML resolves plain
     * {@code 2026-01-01} scalars to {@link Date}; normalise those back to an
     * ISO local-date string (UTC) so the model always holds a stable string.
     */
    private static @Nullable String dateStr(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        return null;
    }

    private static int intOr(@Nullable Object o, int fallback) {
        Integer i = intOrNull(o);
        return i == null ? fallback : i;
    }

    private static boolean boolOr(@Nullable Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    private static @Nullable Double dblOrNull(@Nullable Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return null;
    }

    private static @Nullable Integer intOrNull(@Nullable Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> mm)) return new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) {
            if (e.getKey() != null) m.put(e.getKey().toString(), e.getValue());
        }
        return m;
    }

    private static List<Map<String, Object>> mapList(@Nullable Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o instanceof Map<?, ?>) out.add(asMap(o));
        }
        return out;
    }
}
