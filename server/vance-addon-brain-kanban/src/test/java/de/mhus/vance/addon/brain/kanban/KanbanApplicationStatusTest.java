package de.mhus.vance.addon.brain.kanban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.AppStatus;
import de.mhus.vance.brain.applications.VanceApplication.StatusContext;
import de.mhus.vance.brain.applications.VanceApplication.StatusSeverity;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KanbanApplication#status} — the Common Desktop producer. */
class KanbanApplicationStatusTest {

    private final KanbanFolderReader folderReader = mock(KanbanFolderReader.class);
    private final KanbanApplication app = new KanbanApplication(
            folderReader,
            mock(DocumentService.class),
            mock(DocumentLinkBuilder.class),
            mock(de.mhus.vance.brain.permission.SecurityContextFactory.class));

    private static final StatusContext CTX =
            new StatusContext("t", "p", "board", null, null, Map.of());

    @Test
    void status_reportsDoingColumn_withCardsAndAttentionOnBlocked() {
        KanbanAppConfig config = configWith(
                Map.of("doing", Map.of("title", "In Progress", "order", 3)),
                Map.of("column", "doing", "max", 10));
        stubScan(config, List.of(
                card("doing", "Ship API", "alice", false),
                card("doing", "Fix bug", null, true),
                card("done", "Old task", null, false)));

        Optional<AppStatus> result = app.status(CTX);

        assertThat(result).isPresent();
        AppStatus status = result.get();
        assertThat(status.headline()).isEqualTo("2 in In Progress");
        assertThat(status.items()).hasSize(2);
        assertThat(status.items().get(0).title()).isEqualTo("Ship API");
        assertThat(status.severity()).isEqualTo(StatusSeverity.ATTENTION);
        assertThat(status.metrics()).singleElement()
                .satisfies(m -> {
                    assertThat(m.label()).isEqualTo("In Progress");
                    assertThat(m.value()).isEqualTo("2");
                });
    }

    @Test
    void status_noConfiguredColumnAndNoDoing_returnsEmpty() {
        KanbanAppConfig config = configWith(
                Map.of("backlog", Map.of("order", 1)), Map.of());
        stubScan(config, List.of(card("backlog", "Idea", null, false)));

        assertThat(app.status(CTX)).isEmpty();
    }

    @Test
    void status_wipExceeded_isBlocked() {
        KanbanAppConfig config = configWith(
                Map.of("doing", Map.of("title", "Doing", "order", 2, "wipLimit", 1)),
                Map.of("column", "doing"));
        stubScan(config, List.of(
                card("doing", "A", null, false),
                card("doing", "B", null, false)));

        Optional<AppStatus> result = app.status(CTX);

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(StatusSeverity.BLOCKED);
    }

    // ── Fixtures ──────────────────────────────────────────────────

    private void stubScan(KanbanAppConfig config, List<KanbanFolderReader.CardFile> cards) {
        KanbanFolderReader.Scan scan = new KanbanFolderReader.Scan(
                "board", mock(DocumentDocument.class),
                ApplicationDocument.empty("kanban"), config, cards);
        when(folderReader.scan(any(), any(), any())).thenReturn(scan);
    }

    private static KanbanAppConfig configWith(Map<String, Object> columns,
                                              Map<String, Object> status) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("columns", columns);
        if (!status.isEmpty()) block.put("status", status);
        return KanbanAppConfig.from(block);
    }

    private static KanbanFolderReader.CardFile card(String column, String title,
                                                    String assignee, boolean blocked) {
        CardDocument doc = new CardDocument(
                "card", title, null, assignee, new ArrayList<>(),
                null, null, blocked, "", new LinkedHashMap<>());
        return new KanbanFolderReader.CardFile(mock(DocumentDocument.class), column, doc);
    }
}
