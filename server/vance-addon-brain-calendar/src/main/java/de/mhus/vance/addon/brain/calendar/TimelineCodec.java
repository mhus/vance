package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: timeline} document bodies —
 * mirrors {@code timelineCodec.ts}. JSON and YAML only; markdown is
 * intentionally not supported, for the same reason as {@code calendar}
 * (an axis declaration plus lanes plus four uncertainty bounds per
 * entry does not survive a markdown table).
 *
 * <p>Positions are pass-through strings. The codec does not resolve
 * them against the axis — that is {@link TimelineScale}'s job, used by
 * the validator and mirrored in the renderer. Storing text means the
 * file round-trips as typed; on serialisation a position that is a
 * plain number is emitted unquoted (see
 * {@link ScalarCoercion#numberOrString}), so a numeric axis reads as
 * {@code from: 201.4} rather than {@code from: '201.4'}.
 *
 * <p><b>Permissive on read.</b> Entries without {@code title} or a
 * start position are dropped rather than failing the document — one
 * malformed entry must not cost the reader the other forty. What was
 * dropped is reported by {@link TimelineKindHandler#validate}, which
 * is the surface built for saying so.
 *
 * <p><b>Aliases.</b> {@code at} and {@code start} are read as
 * {@code from}, {@code end} and {@code until} as {@code to}. Models
 * reach for those words constantly — {@code at:} in particular reads
 * better for a point event — and the alternative to accepting them is
 * an entry that vanishes silently. They normalise to the canonical
 * names on write, so a document converges after one save.
 */
public final class TimelineCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private TimelineCodec() {
        // utility class
    }

    public static TimelineDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for timeline: " + mimeType);
    }

    public static String serialize(TimelineDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for timeline: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    /**
     * The un-promoted top-level map, {@code $meta} already flattened.
     * Exists for {@link TimelineKindHandler#validate}, which has to
     * report what the permissive promotion <em>dropped</em> and can
     * only see that by comparing against the raw input.
     */
    static Map<String, Object> rawBody(String body, @Nullable String mimeType) {
        if (body.isBlank()) return new LinkedHashMap<>();
        if (isJson(mimeType)) {
            Map<String, Object> parsed;
            try {
                parsed = JSON.readValue(body, JSON_MAP);
            } catch (JacksonException e) {
                throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
            }
            if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
            return KindHeaderCodec.unwrapJsonMeta(parsed);
        }
        return KindHeaderCodec.parseYamlBody(body);
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

    // ── JSON / YAML ────────────────────────────────────────────────

    private static TimelineDocument parseJson(String body) {
        if (body.isBlank()) return TimelineDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static TimelineDocument parseYaml(String body) {
        if (body.isBlank()) return TimelineDocument.empty();
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(TimelineDocument doc) {
        Map<String, Object> wrapped =
                KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(TimelineDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static TimelineDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";

        TimelineAxis axis = promoteAxis(obj.get("axis"));
        List<TimelineLane> lanes = promoteLanes(obj.get("lanes"));
        List<TimelineEntry> entries = promoteEntries(obj.get("entries"));
        String title = str(obj.get("title"));

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("title");
        extra.remove("axis");
        extra.remove("lanes");
        extra.remove("entries");

        return new TimelineDocument(
                kind.isEmpty() ? "timeline" : kind,
                title, axis, lanes, entries, extra);
    }

    private static TimelineAxis promoteAxis(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return TimelineAxis.defaults();
        TimelineAxis.TimelineAxisMode mode =
                TimelineAxis.TimelineAxisMode.fromWire(str(map.get("mode")));
        TimelineAxis.TimelineDirection direction =
                TimelineAxis.TimelineDirection.fromWire(str(map.get("direction")));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (isKnownAxisKey(key)) continue;
            extra.put(key, e.getValue());
        }

        return new TimelineAxis(
                mode,
                str(map.get("unit")),
                direction,
                str(map.get("from")),
                str(map.get("to")),
                str(map.get("label")),
                extra);
    }

    private static boolean isKnownAxisKey(String key) {
        return switch (key) {
            case "mode", "unit", "direction", "from", "to", "label" -> true;
            default -> false;
        };
    }

    /**
     * Lanes in any of three shapes: a list of ids
     * ({@code lanes: [design, backend]}), a list of objects (canonical),
     * or a map keyed by id — the shape {@code _app.yaml} uses for the
     * calendar application, which a model that has seen one will
     * reproduce here. Insertion order is the render order in all three.
     */
    private static List<TimelineLane> promoteLanes(@Nullable Object raw) {
        List<TimelineLane> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object r : list) {
                if (r instanceof Map<?, ?> map) {
                    String id = str(map.get("id"));
                    if (id == null) continue;
                    out.add(new TimelineLane(id, str(map.get("title")), str(map.get("color"))));
                } else {
                    String id = str(r);
                    if (id != null) out.add(new TimelineLane(id, null, null));
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String id = str(e.getKey());
                if (id == null) continue;
                if (e.getValue() instanceof Map<?, ?> cfg) {
                    out.add(new TimelineLane(id, str(cfg.get("title")), str(cfg.get("color"))));
                } else {
                    out.add(new TimelineLane(id, str(e.getValue()), null));
                }
            }
        }
        return out;
    }

    private static List<TimelineEntry> promoteEntries(@Nullable Object raw) {
        List<TimelineEntry> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;

            String title = str(map.get("title"));
            if (title == null) continue;
            String from = firstPresent(map, "from", "at", "start");
            if (from == null) continue;

            String idCoerced = str(map.get("id"));
            String id = (idCoerced != null) ? idCoerced : UUID.randomUUID().toString();

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (isKnownEntryKey(key)) continue;
                extra.put(key, e.getValue());
            }

            out.add(new TimelineEntry(
                    id,
                    title,
                    from,
                    firstPresent(map, "to", "end", "until"),
                    str(map.get("fromEarliest")),
                    str(map.get("fromLatest")),
                    str(map.get("toEarliest")),
                    str(map.get("toLatest")),
                    str(map.get("lane")),
                    str(map.get("parent")),
                    str(map.get("color")),
                    promoteStringList(map.get("tags")),
                    str(map.get("notes")),
                    extra));
        }
        return out;
    }

    private static boolean isKnownEntryKey(String key) {
        return switch (key) {
            case "id", "title", "from", "at", "start", "to", "end", "until",
                 "fromEarliest", "fromLatest", "toEarliest", "toLatest",
                 "lane", "parent", "color", "tags", "notes" -> true;
            default -> false;
        };
    }

    /** First of {@code keys} present as a readable scalar, else {@code null}. */
    private static @Nullable String firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String v = str(map.get(key));
            if (v != null) return v;
        }
        return null;
    }

    private static List<String> promoteStringList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            String s = str(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static @Nullable String str(@Nullable Object raw) {
        return ScalarCoercion.coerceToString(raw);
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(TimelineDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (doc.title() != null) body.put("title", doc.title());
        body.put("axis", axisToMap(doc.axis()));
        if (!doc.lanes().isEmpty()) body.put("lanes", lanesToList(doc.lanes()));
        body.put("entries", entriesToList(doc.entries()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static Map<String, Object> axisToMap(TimelineAxis axis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", axis.modeWire());
        if (axis.unit() != null) m.put("unit", axis.unit());
        // `forward` is the default; writing it back would add noise to
        // every document that never cared about direction.
        if (axis.direction() != TimelineAxis.TimelineDirection.FORWARD) {
            m.put("direction", axis.directionWire());
        }
        if (axis.from() != null) m.put("from", ScalarCoercion.numberOrString(axis.from()));
        if (axis.to() != null) m.put("to", ScalarCoercion.numberOrString(axis.to()));
        if (axis.label() != null) m.put("label", axis.label());
        for (Map.Entry<String, Object> e : axis.extra().entrySet()) {
            if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    private static List<Map<String, Object>> lanesToList(List<TimelineLane> lanes) {
        List<Map<String, Object>> out = new ArrayList<>(lanes.size());
        for (TimelineLane lane : lanes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", lane.id());
            if (lane.title() != null) m.put("title", lane.title());
            if (lane.color() != null) m.put("color", lane.color());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> entriesToList(List<TimelineEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (TimelineEntry en : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", en.id());
            m.put("title", en.title());
            m.put("from", ScalarCoercion.numberOrString(en.from()));
            putPosition(m, "to", en.to());
            putPosition(m, "fromEarliest", en.fromEarliest());
            putPosition(m, "fromLatest", en.fromLatest());
            putPosition(m, "toEarliest", en.toEarliest());
            putPosition(m, "toLatest", en.toLatest());
            if (en.lane() != null) m.put("lane", en.lane());
            if (en.parent() != null) m.put("parent", en.parent());
            if (en.color() != null) m.put("color", en.color());
            if (!en.tags().isEmpty()) m.put("tags", new ArrayList<>(en.tags()));
            if (en.notes() != null) m.put("notes", en.notes());
            for (Map.Entry<String, Object> ex : en.extra().entrySet()) {
                if (!m.containsKey(ex.getKey())) m.put(ex.getKey(), ex.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static void putPosition(Map<String, Object> target, String key, @Nullable String value) {
        if (value != null) target.put(key, ScalarCoercion.numberOrString(value));
    }

    private static String canonicalKind(TimelineDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "timeline" : doc.kind();
    }
}
