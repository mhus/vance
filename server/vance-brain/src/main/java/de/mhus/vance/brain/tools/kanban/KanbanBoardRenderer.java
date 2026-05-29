package de.mhus.vance.brain.tools.kanban;

import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@code _board.md} artefact for a kanban-app folder.
 * Two output styles, selected via {@code config.kanban.board.style}:
 *
 * <ul>
 *   <li>{@link KanbanAppConfig.BoardStyle#MERMAID} (default) —
 *       native Mermaid {@code kanban} diagram. Renders into a
 *       {@code kind: diagram} Markdown body so the standard diagram
 *       viewer picks it up.</li>
 *   <li>{@link KanbanAppConfig.BoardStyle#TABLE} — Markdown table
 *       with one column per board column, one card per row,
 *       inside a {@code kind: text} Markdown body.</li>
 * </ul>
 *
 * <p>Stateless utility. No IO — the caller writes the produced body.
 */
public final class KanbanBoardRenderer {

    private KanbanBoardRenderer() {
        // utility class
    }

    /**
     * Render the board body.
     *
     * @param scan         the folder scan (manifest + cards).
     * @param fallbackTitle title to use when the manifest has none.
     */
    public static String render(KanbanFolderReader.Scan scan, String fallbackTitle) {
        String title = scan.manifest().title() != null
                ? scan.manifest().title() : fallbackTitle;

        KanbanAppConfig cfg = scan.kanbanConfig();
        Map<String, List<KanbanFolderReader.CardFile>> grouped = groupByColumn(
                scan.cards(), cfg);
        List<String> orderedColumns = orderedColumnNames(cfg, grouped);

        return switch (cfg.board().style()) {
            case TABLE -> renderTable(title, orderedColumns, grouped, cfg);
            case MERMAID -> renderMermaid(title, orderedColumns, grouped, cfg);
        };
    }

    // ── Mermaid ───────────────────────────────────────────────────

    private static String renderMermaid(String title,
                                        List<String> orderedColumns,
                                        Map<String, List<KanbanFolderReader.CardFile>> grouped,
                                        KanbanAppConfig cfg) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("kanban\n");
        for (String col : orderedColumns) {
            KanbanAppConfig.Column colCfg = cfg.columnOrDefault(col);
            String header = colCfg.title() != null ? colCfg.title() : col;
            if (colCfg.wipLimit() != null) {
                int cards = grouped.getOrDefault(col, List.of()).size();
                header = header + " (" + cards + "/" + colCfg.wipLimit() + ")";
            }
            mermaid.append("  ").append(header).append('\n');
            List<KanbanFolderReader.CardFile> cards = grouped.getOrDefault(col, List.of());
            int max = cfg.board().maxCardsPerColumn();
            int shown = Math.min(cards.size(), max);
            for (int i = 0; i < shown; i++) {
                KanbanFolderReader.CardFile cf = cards.get(i);
                CardDocument card = cf.card();
                String cardId = sanitiseMermaidId(cf.doc().getPath());
                String label = mermaidEscape(displayTitle(card, cf.doc().getPath()));
                mermaid.append("    ").append(cardId).append('[').append(label).append(']');
                appendMermaidMetadata(mermaid, card);
                mermaid.append('\n');
            }
            if (cards.size() > shown) {
                mermaid.append("    overflow_").append(sanitiseMermaidId(col))
                        .append("[+ ").append(cards.size() - shown).append(" more]\n");
            }
        }
        return wrapAsDiagramMarkdown(mermaid.toString(), title);
    }

    private static void appendMermaidMetadata(StringBuilder out, CardDocument card) {
        List<String> bits = new ArrayList<>();
        if (card.assignee() != null) {
            bits.add("assigned: '" + mermaidQuoteValue(card.assignee()) + "'");
        }
        if (card.priority() != null) {
            bits.add("priority: '" + mermaidQuoteValue(card.priority()) + "'");
        }
        if (card.dueDate() != null) {
            bits.add("ticket: '" + mermaidQuoteValue(card.dueDate()) + "'");
        }
        if (bits.isEmpty()) return;
        out.append("@{ ").append(String.join(", ", bits)).append(" }");
    }

    /** Wrap Mermaid source in a {@code kind: diagram} Markdown body. */
    public static String wrapAsDiagramMarkdown(String mermaidSource, String title) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("kind: diagram\n");
        if (title != null && !title.isBlank()) {
            out.append("title: ").append(title.replace('\n', ' ')).append('\n');
        }
        out.append("---\n\n");
        out.append("```mermaid\n");
        out.append(mermaidSource);
        if (!mermaidSource.endsWith("\n")) out.append('\n');
        out.append("```\n");
        return out.toString();
    }

    // ── Markdown table ────────────────────────────────────────────

    private static String renderTable(String title,
                                      List<String> orderedColumns,
                                      Map<String, List<KanbanFolderReader.CardFile>> grouped,
                                      KanbanAppConfig cfg) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("kind: text\n");
        out.append("---\n\n");
        out.append("# ").append(title).append("\n\n");

        if (orderedColumns.isEmpty()) {
            out.append("_No columns defined and no cards present._\n");
            return out.toString();
        }

        // Header.
        out.append('|');
        for (String col : orderedColumns) {
            KanbanAppConfig.Column c = cfg.columnOrDefault(col);
            String h = c.title() != null ? c.title() : col;
            int cards = grouped.getOrDefault(col, List.of()).size();
            if (c.wipLimit() != null) {
                h = h + " (" + cards + "/" + c.wipLimit() + ")";
            } else {
                h = h + " (" + cards + ")";
            }
            out.append(' ').append(escapeTableCell(h)).append(" |");
        }
        out.append('\n').append('|');
        for (int i = 0; i < orderedColumns.size(); i++) out.append("---|");
        out.append('\n');

        // Rows — pad shorter columns with empty cells so the table stays rectangular.
        int rows = 0;
        for (String col : orderedColumns) {
            rows = Math.max(rows, grouped.getOrDefault(col, List.of()).size());
        }
        rows = Math.min(rows, cfg.board().maxCardsPerColumn());
        for (int r = 0; r < rows; r++) {
            out.append('|');
            for (String col : orderedColumns) {
                List<KanbanFolderReader.CardFile> cards = grouped.getOrDefault(col, List.of());
                if (r < cards.size()) {
                    out.append(' ').append(formatTableCell(cards.get(r))).append(" |");
                } else {
                    out.append("  |");
                }
            }
            out.append('\n');
        }

        // Overflow note.
        for (String col : orderedColumns) {
            int size = grouped.getOrDefault(col, List.of()).size();
            if (size > cfg.board().maxCardsPerColumn()) {
                out.append("\n_+").append(size - cfg.board().maxCardsPerColumn())
                        .append(" more in ").append(col).append("._\n");
            }
        }
        return out.toString();
    }

    private static String formatTableCell(KanbanFolderReader.CardFile cf) {
        CardDocument card = cf.card();
        String title = displayTitle(card, cf.doc().getPath());
        StringBuilder cell = new StringBuilder();
        cell.append(escapeTableCell(title));
        List<String> tail = new ArrayList<>();
        if (card.assignee() != null) tail.add("@" + card.assignee());
        if (card.priority() != null) tail.add(card.priority());
        if (card.dueDate() != null) tail.add(card.dueDate());
        if (card.blocked()) tail.add("blocked");
        int[] cb = de.mhus.vance.shared.document.kind.CardCodec.countCheckboxes(card.body());
        if (cb[0] > 0) tail.add(cb[1] + "/" + cb[0]);
        if (!tail.isEmpty()) {
            cell.append("<br/>").append(escapeTableCell(String.join(" · ", tail)));
        }
        return cell.toString();
    }

    private static String escapeTableCell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    // ── Column ordering / grouping ────────────────────────────────

    private static Map<String, List<KanbanFolderReader.CardFile>> groupByColumn(
            List<KanbanFolderReader.CardFile> cards, KanbanAppConfig cfg) {
        Map<String, List<KanbanFolderReader.CardFile>> grouped = new LinkedHashMap<>();
        for (KanbanFolderReader.CardFile cf : cards) {
            grouped.computeIfAbsent(cf.column(), k -> new ArrayList<>()).add(cf);
        }
        // Sort each column's cards: priority desc, then dueDate asc, then title asc.
        Comparator<KanbanFolderReader.CardFile> cmp = Comparator
                .comparingInt((KanbanFolderReader.CardFile cf) -> -priorityWeight(cf.card().priority()))
                .thenComparing((KanbanFolderReader.CardFile cf) ->
                        cf.card().dueDate() != null ? cf.card().dueDate() : "9999-99-99")
                .thenComparing(cf -> displayTitle(cf.card(), cf.doc().getPath()));
        for (List<KanbanFolderReader.CardFile> lst : grouped.values()) {
            lst.sort(cmp);
        }
        return grouped;
    }

    private static List<String> orderedColumnNames(
            KanbanAppConfig cfg,
            Map<String, List<KanbanFolderReader.CardFile>> grouped) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        // 1) Manifest columnOrder list takes precedence.
        for (String name : cfg.board().columnOrder()) {
            if (seen.add(name)) out.add(name);
        }
        // 2) Manifest columns sorted by `order:` (then insertion order).
        List<Map.Entry<String, KanbanAppConfig.Column>> declared =
                new ArrayList<>(cfg.columns().entrySet());
        declared.sort(Comparator.comparingInt(
                e -> e.getValue().order() != null ? e.getValue().order() : Integer.MAX_VALUE));
        for (Map.Entry<String, KanbanAppConfig.Column> e : declared) {
            if (seen.add(e.getKey())) out.add(e.getKey());
        }
        // 3) Any column that exists on disk but wasn't declared.
        for (String col : grouped.keySet()) {
            if (seen.add(col)) out.add(col);
        }
        return out;
    }

    private static int priorityWeight(@Nullable String priority) {
        if (priority == null) return 1;
        return switch (priority.toLowerCase(java.util.Locale.ROOT)) {
            case "critical" -> 4;
            case "high" -> 3;
            case "med", "medium", "normal" -> 2;
            case "low" -> 0;
            default -> 1;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Card title falls back to the filename stem when missing. */
    private static String displayTitle(CardDocument card, String path) {
        if (!card.title().isEmpty()) return card.title();
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    private static String sanitiseMermaidId(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (char c : path.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0 || !Character.isLetter(sb.charAt(0))) {
            sb.insert(0, 'c');
        }
        return sb.toString();
    }

    /** Escape a label so it can sit inside Mermaid's {@code [..]} bracket. */
    private static String mermaidEscape(String s) {
        return s.replace("[", "(").replace("]", ")").replace("\n", " ");
    }

    private static String mermaidQuoteValue(String s) {
        return s.replace("'", "\\'").replace("\n", " ");
    }
}
