package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.ArtefactResult;
import de.mhus.vance.brain.applications.VanceApplication.CreateContext;
import de.mhus.vance.brain.applications.VanceApplication.CreateResult;
import de.mhus.vance.brain.applications.VanceApplication.RefreshContext;
import de.mhus.vance.brain.applications.VanceApplication.RefreshResult;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@link KanbanFolderReader.Scan} into the API-shaped
 * {@link KanbanBoardView}. Stateless utility — every operation is
 * idempotent on the input.
 */
public final class KanbanBoardMapper {

    private KanbanBoardMapper() {
        // utility class
    }

    /**
     * Build the board view from a folder scan. Columns are ordered
     * (manifest {@code columnOrder} → manifest {@code order} →
     * on-disk leftovers); cards stay in their natural scan order
     * — the client sorts per column.
     */
    public static KanbanBoardView toView(KanbanFolderReader.Scan scan,
                                         List<VanceApplication.ArtefactResult> artefacts) {
        KanbanAppConfig cfg = scan.kanbanConfig();

        Map<String, Integer> countsByColumn = new LinkedHashMap<>();
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            countsByColumn.merge(cf.column(), 1, Integer::sum);
        }

        List<KanbanColumnView> columns = buildColumns(cfg, countsByColumn);
        List<KanbanCardView> cards = buildCards(scan.cards());

        List<KanbanArtefactSummary> arts = new ArrayList<>();
        if (artefacts != null) {
            for (VanceApplication.ArtefactResult a : artefacts) {
                arts.add(KanbanArtefactSummary.builder()
                        .name(a.name())
                        .path(a.path())
                        .markdownLink(a.markdownLink())
                        .build());
            }
        }

        return KanbanBoardView.builder()
                .folder(scan.folder())
                .manifestPath(scan.manifestDoc() != null ? scan.manifestDoc().getPath() : null)
                .title(scan.manifest().title())
                .description(scan.manifest().description())
                .wipEnforce(cfg.wipEnforce().name().toLowerCase(java.util.Locale.ROOT))
                .boardStyle(cfg.board().style().wireName())
                .columns(columns)
                .cards(cards)
                .artefacts(arts)
                .build();
    }

    private static List<KanbanColumnView> buildColumns(
            KanbanAppConfig cfg, Map<String, Integer> countsByColumn) {

        Set<String> seen = new LinkedHashSet<>();
        List<KanbanColumnView> out = new ArrayList<>();

        // 1) Manifest columnOrder
        for (String name : cfg.board().columnOrder()) {
            if (seen.add(name)) {
                out.add(viewFor(name, cfg.columns().get(name),
                        countsByColumn.getOrDefault(name, 0), true));
            }
        }
        // 2) Manifest columns sorted by order:
        List<Map.Entry<String, KanbanAppConfig.Column>> declared =
                new ArrayList<>(cfg.columns().entrySet());
        declared.sort(Comparator.comparingInt(
                e -> e.getValue().order() != null ? e.getValue().order() : Integer.MAX_VALUE));
        for (Map.Entry<String, KanbanAppConfig.Column> e : declared) {
            if (seen.add(e.getKey())) {
                out.add(viewFor(e.getKey(), e.getValue(),
                        countsByColumn.getOrDefault(e.getKey(), 0), true));
            }
        }
        // 3) Undeclared columns existing on disk
        for (String col : countsByColumn.keySet()) {
            if (seen.add(col)) {
                out.add(viewFor(col, null, countsByColumn.get(col), false));
            }
        }
        return out;
    }

    private static KanbanColumnView viewFor(String name,
                                            KanbanAppConfig.Column declared,
                                            int count,
                                            boolean isDeclared) {
        String title = (declared != null && declared.title() != null) ? declared.title() : name;
        String color = declared != null ? declared.color() : null;
        Integer order = declared != null ? declared.order() : null;
        Integer wipLimit = declared != null ? declared.wipLimit() : null;
        boolean wipExceeded = wipLimit != null && count > wipLimit;
        return KanbanColumnView.builder()
                .name(name)
                .title(title)
                .color(color)
                .order(order)
                .wipLimit(wipLimit)
                .cardCount(count)
                .wipExceeded(wipExceeded)
                .declared(isDeclared)
                .build();
    }

    private static List<KanbanCardView> buildCards(List<KanbanFolderReader.CardFile> cards) {
        List<KanbanCardView> out = new ArrayList<>(cards.size());
        for (KanbanFolderReader.CardFile cf : cards) {
            int[] cb = CardCodec.countCheckboxes(cf.card().body());
            out.add(KanbanCardView.builder()
                    .path(cf.doc().getPath())
                    .column(cf.column())
                    .title(displayTitle(cf))
                    .priority(cf.card().priority())
                    .assignee(cf.card().assignee())
                    .labels(new ArrayList<>(cf.card().labels()))
                    .dueDate(cf.card().dueDate())
                    .estimate(cf.card().estimate())
                    .blocked(cf.card().blocked())
                    .body(cf.card().body().isEmpty() ? null : cf.card().body())
                    .subtaskTotal(cb[0])
                    .subtaskDone(cb[1])
                    .build());
        }
        return out;
    }

    private static String displayTitle(KanbanFolderReader.CardFile cf) {
        String t = cf.card().title();
        if (t != null && !t.isEmpty()) return t;
        String path = cf.doc().getPath();
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }
}
