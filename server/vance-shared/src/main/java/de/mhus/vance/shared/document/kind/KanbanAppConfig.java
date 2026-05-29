package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view onto the {@code config.kanban} block of an
 * {@link ApplicationDocument} with {@code app: kanban}.
 *
 * <p>Read-only — call {@link #from(ApplicationDocument)} (or
 * {@link #from(Map)} for direct testing) and use the records.
 *
 * <p>Schema (under {@code config.kanban} in the manifest):
 * <pre>
 * kanban:
 *   columns:
 *     backlog: { title: "Backlog",     order: 1 }
 *     todo:    { title: "To Do",       order: 2, wipLimit: 5 }
 *     doing:   { title: "In Progress", order: 3, wipLimit: 3 }
 *     review:  { title: "Review",      order: 4, wipLimit: 2 }
 *     done:    { title: "Done",        order: 5 }
 *   board:
 *     outputPath: _board.md
 *     style: mermaid           # mermaid | table
 *     maxCardsPerColumn: 20
 *   stats:
 *     outputPath: _stats.yaml
 *     blockedLabel: blocked
 *     staleThresholdDays: 14
 *   wipEnforce: soft           # soft | hard — controls kanban_move
 * </pre>
 */
public final class KanbanAppConfig {

    public static final String APP_NAME = "kanban";

    public enum BoardStyle {
        MERMAID, TABLE;

        public static BoardStyle fromWire(@Nullable String s) {
            if (s == null) return MERMAID;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "table" -> TABLE;
                case "mermaid" -> MERMAID;
                default -> MERMAID;
            };
        }

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum WipEnforce {
        SOFT, HARD;

        public static WipEnforce fromWire(@Nullable String s) {
            if (s == null) return SOFT;
            return "hard".equalsIgnoreCase(s.trim()) ? HARD : SOFT;
        }
    }

    public record Column(
            String name,
            @Nullable String title,
            @Nullable String color,
            @Nullable Integer order,
            @Nullable Integer wipLimit,
            @Nullable String description) { }

    public record BoardConfig(
            String outputPath,
            BoardStyle style,
            int maxCardsPerColumn,
            List<String> columnOrder) {

        public static BoardConfig defaults() {
            return new BoardConfig("_board.md", BoardStyle.MERMAID, 20, List.of());
        }
    }

    public record StatsConfig(
            String outputPath,
            String blockedLabel,
            int staleThresholdDays) {

        public static StatsConfig defaults() {
            return new StatsConfig("_stats.yaml", "blocked", 14);
        }
    }

    private final Map<String, Column> columns;
    private final BoardConfig board;
    private final StatsConfig stats;
    private final WipEnforce wipEnforce;

    private KanbanAppConfig(Map<String, Column> columns, BoardConfig board,
                            StatsConfig stats, WipEnforce wipEnforce) {
        this.columns = columns;
        this.board = board;
        this.stats = stats;
        this.wipEnforce = wipEnforce;
    }

    public Map<String, Column> columns() { return columns; }
    public BoardConfig board() { return board; }
    public StatsConfig stats() { return stats; }
    public WipEnforce wipEnforce() { return wipEnforce; }

    /** Look up a column config; falls back to a name-only default. */
    public Column columnOrDefault(String name) {
        Column explicit = columns.get(name);
        if (explicit != null) return explicit;
        return new Column(name, name, null, null, null, null);
    }

    // ── Entry points ──────────────────────────────────────────────

    public static KanbanAppConfig from(ApplicationDocument doc) {
        if (!APP_NAME.equalsIgnoreCase(doc.app())) {
            throw new KindCodecException(
                    "ApplicationDocument is app='" + doc.app()
                            + "', cannot reinterpret as kanban.");
        }
        Object raw = doc.config().get(APP_NAME);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return from(typed);
        }
        return new KanbanAppConfig(
                new LinkedHashMap<>(),
                BoardConfig.defaults(),
                StatsConfig.defaults(),
                WipEnforce.SOFT);
    }

    public static KanbanAppConfig from(Map<String, Object> block) {
        Map<String, Column> columns = readColumns(block.get("columns"));
        BoardConfig board = readBoard(block.get("board"));
        StatsConfig stats = readStats(block.get("stats"));
        WipEnforce wipEnforce = WipEnforce.fromWire(stringOrNull(block.get("wipEnforce")));
        return new KanbanAppConfig(columns, board, stats, wipEnforce);
    }

    // ── Readers ───────────────────────────────────────────────────

    private static Map<String, Column> readColumns(@Nullable Object raw) {
        Map<String, Column> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String name)) continue;
            if (!(e.getValue() instanceof Map<?, ?> colMap)) {
                out.put(name, new Column(name, name, null, null, null, null));
                continue;
            }
            String title = stringOrNull(colMap.get("title"));
            String color = stringOrNull(colMap.get("color"));
            Integer order = (colMap.get("order") instanceof Number n) ? n.intValue() : null;
            Integer wipLimit = (colMap.get("wipLimit") instanceof Number n2) ? n2.intValue() : null;
            String description = stringOrNull(colMap.get("description"));
            out.put(name, new Column(
                    name,
                    title != null ? title : name,
                    color, order, wipLimit, description));
        }
        return out;
    }

    private static BoardConfig readBoard(@Nullable Object raw) {
        BoardConfig d = BoardConfig.defaults();
        if (!(raw instanceof Map<?, ?> map)) return d;
        String outputPath = stringOrDefault(map.get("outputPath"), d.outputPath());
        BoardStyle style = BoardStyle.fromWire(stringOrNull(map.get("style")));
        int maxCards = (map.get("maxCardsPerColumn") instanceof Number n)
                ? Math.max(1, n.intValue()) : d.maxCardsPerColumn();
        List<String> columnOrder = stringList(map.get("columnOrder"), d.columnOrder());
        return new BoardConfig(outputPath, style, maxCards, columnOrder);
    }

    private static StatsConfig readStats(@Nullable Object raw) {
        StatsConfig d = StatsConfig.defaults();
        if (!(raw instanceof Map<?, ?> map)) return d;
        String outputPath = stringOrDefault(map.get("outputPath"), d.outputPath());
        String blockedLabel = stringOrDefault(map.get("blockedLabel"), d.blockedLabel());
        int stale = (map.get("staleThresholdDays") instanceof Number n)
                ? Math.max(0, n.intValue()) : d.staleThresholdDays();
        return new StatsConfig(outputPath, blockedLabel, stale);
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
