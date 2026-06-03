package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view onto the {@code config.calendar} block of an
 * {@link ApplicationDocument} with {@code app: calendar}.
 *
 * <p>Read-only — call {@link #from(ApplicationDocument)} (or
 * {@link #from(Map)} for direct testing) and use the records. The
 * helper is intentionally not a Spring bean so the calendar tools
 * can reach for it from anywhere without dragging context along.
 *
 * <p>Schema (under {@code config.calendar} in the manifest):
 * <pre>
 * calendar:
 *   window:
 *     from: "2026-06-01"
 *     until: "2026-09-30"
 *   lanes:
 *     design:   { title: "Design",  color: blue,  order: 1 }
 *     backend:  { title: "Backend", color: green, order: 2 }
 *   gantt:
 *     outputPath: _gantt.md
 *     includeRecurring: false
 *     tagFilter: []
 *     criticalTags: [milestone, critical]
 *     doneTags: [done, erledigt]
 *     sectionOrder: [design, backend]
 *   conflicts:
 *     outputPath: _conflicts.yaml
 *     ignoreWithinTags: [private]
 *     ignoreAllDayOverlapsBetweenLanes: false
 * </pre>
 */
public final class CalendarsAppConfig {

    public static final String APP_NAME = "calendar";

    public record Window(@Nullable String from, @Nullable String until) { }

    public record Lane(
            String name,
            @Nullable String title,
            @Nullable String color,
            @Nullable Integer order,
            @Nullable String description) { }

    public record GanttConfig(
            String outputPath,
            boolean includeRecurring,
            List<String> tagFilter,
            List<String> criticalTags,
            List<String> doneTags,
            List<String> sectionOrder) {

        public static GanttConfig defaults() {
            return new GanttConfig("_gantt.md", false,
                    List.of(),
                    List.of("milestone", "critical"),
                    List.of("done", "erledigt"),
                    List.of());
        }
    }

    public record ConflictsConfig(
            String outputPath,
            List<String> ignoreWithinTags,
            boolean ignoreAllDayOverlapsBetweenLanes) {

        public static ConflictsConfig defaults() {
            return new ConflictsConfig("_conflicts.yaml", List.of(), false);
        }
    }

    private final Window window;
    private final Map<String, Lane> lanes;
    private final GanttConfig gantt;
    private final ConflictsConfig conflicts;

    private CalendarsAppConfig(Window window, Map<String, Lane> lanes,
                               GanttConfig gantt, ConflictsConfig conflicts) {
        this.window = window;
        this.lanes = lanes;
        this.gantt = gantt;
        this.conflicts = conflicts;
    }

    public Window window() { return window; }
    public Map<String, Lane> lanes() { return lanes; }
    public GanttConfig gantt() { return gantt; }
    public ConflictsConfig conflicts() { return conflicts; }

    /**
     * Resolve the lane config for the given lane name. Auto-defaults
     * apply when the manifest doesn't list the lane: {@code title}
     * falls back to the name, every other field is {@code null}.
     */
    public Lane laneOrDefault(String name) {
        Lane explicit = lanes.get(name);
        if (explicit != null) return explicit;
        return new Lane(name, name, null, null, null);
    }

    // ── Entry points ──────────────────────────────────────────────

    /**
     * Read the calendar-specific config out of an
     * {@link ApplicationDocument}. Returns sensible defaults when the
     * manifest doesn't have a {@code calendar} block — the tools can
     * then run on auto-detected lanes / events alone.
     *
     * @throws KindCodecException when {@code doc.app()} isn't
     *         {@code "calendar"} — the helper is a typed lens over
     *         that one app, not a polyglot reader.
     */
    public static CalendarsAppConfig from(ApplicationDocument doc) {
        if (!APP_NAME.equalsIgnoreCase(doc.app())) {
            throw new KindCodecException(
                    "ApplicationDocument is app='" + doc.app()
                            + "', cannot reinterpret as calendar.");
        }
        Object raw = doc.config().get(APP_NAME);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) map;
            return from(typedMap);
        }
        return new CalendarsAppConfig(
                new Window(null, null),
                new LinkedHashMap<>(),
                GanttConfig.defaults(),
                ConflictsConfig.defaults());
    }

    /**
     * Direct entry — useful in tests and when callers already have a
     * config map in hand. Equivalent to {@link #from(ApplicationDocument)}
     * applied to the calendar sub-block.
     */
    @SuppressWarnings("unchecked")
    public static CalendarsAppConfig from(Map<String, Object> calBlock) {
        Window window = readWindow(calBlock.get("window"));
        Map<String, Lane> lanes = readLanes(calBlock.get("lanes"));
        GanttConfig gantt = readGantt(calBlock.get("gantt"));
        ConflictsConfig conflicts = readConflicts(calBlock.get("conflicts"));
        return new CalendarsAppConfig(window, lanes, gantt, conflicts);
    }

    // ── Readers ───────────────────────────────────────────────────

    private static Window readWindow(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return new Window(null, null);
        return new Window(
                stringOrNull(map.get("from")),
                stringOrNull(map.get("until")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Lane> readLanes(@Nullable Object raw) {
        Map<String, Lane> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String name)) continue;
            if (!(e.getValue() instanceof Map<?, ?> laneMap)) {
                out.put(name, new Lane(name, name, null, null, null));
                continue;
            }
            String title = stringOrNull(laneMap.get("title"));
            String color = stringOrNull(laneMap.get("color"));
            Integer order = (laneMap.get("order") instanceof Number n) ? n.intValue() : null;
            String description = stringOrNull(laneMap.get("description"));
            out.put(name, new Lane(
                    name,
                    title != null ? title : name,
                    color, order, description));
        }
        return out;
    }

    private static GanttConfig readGantt(@Nullable Object raw) {
        GanttConfig d = GanttConfig.defaults();
        if (!(raw instanceof Map<?, ?> map)) return d;
        String outputPath = stringOrDefault(map.get("outputPath"), d.outputPath());
        boolean includeRecurring = boolOrDefault(map.get("includeRecurring"), d.includeRecurring());
        List<String> tagFilter = stringList(map.get("tagFilter"), d.tagFilter());
        List<String> criticalTags = stringList(map.get("criticalTags"), d.criticalTags());
        List<String> doneTags = stringList(map.get("doneTags"), d.doneTags());
        List<String> sectionOrder = stringList(map.get("sectionOrder"), d.sectionOrder());
        return new GanttConfig(outputPath, includeRecurring,
                tagFilter, criticalTags, doneTags, sectionOrder);
    }

    private static ConflictsConfig readConflicts(@Nullable Object raw) {
        ConflictsConfig d = ConflictsConfig.defaults();
        if (!(raw instanceof Map<?, ?> map)) return d;
        String outputPath = stringOrDefault(map.get("outputPath"), d.outputPath());
        List<String> ignoreWithinTags = stringList(map.get("ignoreWithinTags"),
                d.ignoreWithinTags());
        boolean ignoreAllDay = boolOrDefault(
                map.get("ignoreAllDayOverlapsBetweenLanes"),
                d.ignoreAllDayOverlapsBetweenLanes());
        return new ConflictsConfig(outputPath, ignoreWithinTags, ignoreAllDay);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s;
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String stringOrDefault(@Nullable Object v, String fallback) {
        String s = stringOrNull(v);
        return s != null ? s : fallback;
    }

    private static boolean boolOrDefault(@Nullable Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static List<String> stringList(@Nullable Object raw, List<String> fallback) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>(fallback);
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s);
            else if (o != null) out.add(o.toString());
        }
        return out;
    }
}
