package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates a {@code _stats.yaml} body for a kanban suite. Output is
 * a {@code kind: data} document with three top-level sections:
 *
 * <ul>
 *   <li>{@code columns} — per-column count + WIP-limit status.</li>
 *   <li>{@code blocked} — list of card paths tagged blocked.</li>
 *   <li>{@code stale} — list of card paths untouched longer than
 *       {@code staleThresholdDays}.</li>
 *   <li>{@code progress} — total / done / open / ratio. {@code done}
 *       counts cards in a column whose name matches one of
 *       {@code done|completed|closed} (case-insensitive). The board
 *       owner doesn't have to model "done" specially.</li>
 * </ul>
 *
 * <p>Stateless utility. The caller wraps the returned body into a
 * {@code DataDocument} and serialises it.
 */
public final class KanbanStatsBuilder {

    private static final List<String> DONE_COLUMN_NAMES = List.of(
            "done", "completed", "closed", "erledigt");

    private KanbanStatsBuilder() {
        // utility class
    }

    public static Map<String, Object> build(KanbanFolderReader.Scan scan) {
        KanbanAppConfig cfg = scan.kanbanConfig();
        String blockedLabel = cfg.stats().blockedLabel();
        int staleDays = cfg.stats().staleThresholdDays();

        // Group cards by column (column = on-disk folder).
        Map<String, List<KanbanFolderReader.CardFile>> grouped = new LinkedHashMap<>();
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            grouped.computeIfAbsent(cf.column(), k -> new ArrayList<>()).add(cf);
        }

        // Stable iteration: manifest columns first (in their order),
        // then any extras that exist on disk.
        List<String> orderedColumns = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String name : cfg.board().columnOrder()) {
            if (seen.add(name)) orderedColumns.add(name);
        }
        for (Map.Entry<String, KanbanAppConfig.Column> e : cfg.columns().entrySet()) {
            if (seen.add(e.getKey())) orderedColumns.add(e.getKey());
        }
        for (String col : grouped.keySet()) {
            if (seen.add(col)) orderedColumns.add(col);
        }

        // Per-column stats.
        Map<String, Object> columnsOut = new LinkedHashMap<>();
        int totalCards = 0;
        int doneCards = 0;
        for (String col : orderedColumns) {
            List<KanbanFolderReader.CardFile> cards = grouped.getOrDefault(col, List.of());
            int count = cards.size();
            totalCards += count;
            if (isDoneColumn(col)) doneCards += count;

            Map<String, Object> colStats = new LinkedHashMap<>();
            colStats.put("count", count);
            KanbanAppConfig.Column declared = cfg.columns().get(col);
            if (declared != null && declared.wipLimit() != null) {
                colStats.put("wipLimit", declared.wipLimit());
                colStats.put("wipExceeded", count > declared.wipLimit());
            }
            if (declared == null) {
                colStats.put("undeclared", true);
            }
            columnsOut.put(col, colStats);
        }

        // Blocked + stale lists.
        List<String> blocked = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        LocalDate today = LocalDate.now();
        int totalCheckboxes = 0;
        int doneCheckboxes = 0;
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            CardDocument card = cf.card();
            if (isBlocked(card, blockedLabel)) {
                blocked.add(cf.doc().getPath());
            }
            if (staleDays > 0 && isStale(cf, today, staleDays)) {
                stale.add(cf.doc().getPath());
            }
            int[] cb = CardCodec.countCheckboxes(card.body());
            totalCheckboxes += cb[0];
            doneCheckboxes += cb[1];
        }

        // Progress.
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("totalCards", totalCards);
        progress.put("done", doneCards);
        progress.put("open", totalCards - doneCards);
        progress.put("ratio", totalCards == 0
                ? 0.0
                : round2(doneCards / (double) totalCards));
        if (totalCheckboxes > 0) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("total", totalCheckboxes);
            sub.put("done", doneCheckboxes);
            sub.put("ratio", round2(doneCheckboxes / (double) totalCheckboxes));
            progress.put("subtasks", sub);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("folder", scan.folder());
        body.put("columns", columnsOut);
        body.put("blocked", blocked);
        body.put("stale", stale);
        body.put("progress", progress);
        return body;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static boolean isDoneColumn(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return DONE_COLUMN_NAMES.contains(lower);
    }

    private static boolean isBlocked(CardDocument card, String blockedLabel) {
        if (card.blocked()) return true;
        if (blockedLabel == null || blockedLabel.isBlank()) return false;
        for (String label : card.labels()) {
            if (blockedLabel.equalsIgnoreCase(label)) return true;
        }
        return false;
    }

    /** Stale-detection uses {@code createdAt} as a proxy because the
     *  document store doesn't track per-update timestamps yet. Good
     *  enough for the "card sat in Backlog forever" case; over-counts
     *  for cards that get touched in place. Improving this needs a
     *  {@code modifiedAt} on {@code DocumentDocument}. */
    private static boolean isStale(KanbanFolderReader.CardFile cf,
                                   LocalDate today, int staleDays) {
        Instant created = cf.doc().getCreatedAt();
        if (created == null) return false;
        LocalDate createdDay = created.atZone(ZoneId.systemDefault()).toLocalDate();
        long days = ChronoUnit.DAYS.between(createdDay, today);
        return days >= staleDays;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
