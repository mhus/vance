package de.mhus.vance.addon.brain.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.AppCard;
import de.mhus.vance.brain.applications.VanceApplication.AppStatus;
import de.mhus.vance.brain.applications.VanceApplication.StatusItem;
import de.mhus.vance.brain.applications.VanceApplication.StatusSeverity;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DesktopStatusService} aggregation. */
class DesktopStatusServiceTest {

    private static final String YAML = "application/yaml";

    private final DocumentService documentService = mock(DocumentService.class);
    private final VanceApplicationRegistry registry = mock(VanceApplicationRegistry.class);
    private final Map<String, String> bodies = new HashMap<>();
    private DesktopStatusService service;

    @BeforeEach
    void setUp() {
        service = new DesktopStatusService(documentService, registry);
        lenient().when(registry.find(any())).thenReturn(Optional.empty());
        // Central content lookup keyed by path — keeps the appDoc()
        // helpers free of nested when() (which would break Mockito when
        // called inside a thenReturn(List.of(...)) argument).
        lenient().when(documentService.readContent(any())).thenAnswer(
                inv -> bodies.get(((DocumentDocument) inv.getArgument(0)).getPath()));
    }

    @Test
    void aggregate_directChildren_excludesSelfNestedAndOutside() {
        deskManifest("desk", Map.of());   // recurse=false (default)
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/_app.yaml", "common-desktop"),          // self → skip
                appDoc("desk/nested/_app.yaml", "common-desktop"),   // nested desktop → skip
                appDoc("desk/board/_app.yaml", "kanban"),            // direct child → keep
                appDoc("desk/deep/sub/_app.yaml", "kanban"),         // grandchild → skip (no recurse)
                appDoc("other/x/_app.yaml", "kanban")));             // outside root → skip

        DesktopView view = service.aggregate("t", "p", "desk", null);

        assertThat(view.getCards()).extracting(DesktopCard::getFolder)
                .containsExactly("desk/board");
    }

    @Test
    void aggregate_recurse_includesGrandchildren() {
        deskManifest("desk", Map.of("recurse", true));
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/board/_app.yaml", "kanban"),
                appDoc("desk/deep/sub/_app.yaml", "kanban")));

        DesktopView view = service.aggregate("t", "p", "desk", null);

        assertThat(view.getCards()).extracting(DesktopCard::getFolder)
                .containsExactly("desk/board", "desk/deep/sub");
    }

    @Test
    void aggregate_unknownAppType_fallsBackToLauncherCard() {
        deskManifest("desk", Map.of());
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/notes/_app.yaml", "workbook")));   // registry.find → empty

        DesktopView view = service.aggregate("t", "p", "desk", null);

        assertThat(view.getCards()).singleElement().satisfies(c -> {
            assertThat(c.getApp()).isEqualTo("workbook");
            assertThat(c.getIcon()).isEqualTo("📦");
            assertThat(c.getOpenLink()).isEqualTo("vance:/desk/notes/_app.yaml");
            assertThat(c.getStatus()).isNull();
        });
    }

    @Test
    void aggregate_appWithStatus_mapsDescribeAndStatus() {
        deskManifest("desk", Map.of());
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/board/_app.yaml", "kanban")));

        VanceApplication kanban = mock(VanceApplication.class);
        when(kanban.describe(any())).thenReturn(new AppCard("📋", "vance:/desk/board"));
        when(kanban.status(any())).thenReturn(Optional.of(
                AppStatus.of("3 in Doing", StatusSeverity.OK, List.of(StatusItem.of("Ship")))));
        when(registry.find("kanban")).thenReturn(Optional.of(kanban));

        DesktopView view = service.aggregate("t", "p", "desk", null);

        DesktopCard card = view.getCards().get(0);
        assertThat(card.getIcon()).isEqualTo("📋");
        assertThat(card.getOpenLink()).isEqualTo("vance:/desk/board");
        assertThat(card.getStatus()).isNotNull();
        assertThat(card.getStatus().getHeadline()).isEqualTo("3 in Doing");
        assertThat(card.getStatus().getSeverity()).isEqualTo("ok");
        assertThat(card.getStatus().getItems()).singleElement()
                .satisfies(i -> assertThat(i.getTitle()).isEqualTo("Ship"));
    }

    @Test
    void aggregate_statusThrows_degradesToErrorCard() {
        deskManifest("desk", Map.of());
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/board/_app.yaml", "kanban")));

        VanceApplication kanban = mock(VanceApplication.class);
        when(kanban.describe(any())).thenReturn(new AppCard("📋", null));
        when(kanban.status(any())).thenThrow(new RuntimeException("boom"));
        when(registry.find("kanban")).thenReturn(Optional.of(kanban));

        DesktopView view = service.aggregate("t", "p", "desk", null);

        DesktopCard card = view.getCards().get(0);
        assertThat(card.getStatus()).isNotNull();
        assertThat(card.getStatus().getSeverity()).isEqualTo("blocked");
        assertThat(card.getStatus().getHeadline()).contains("boom");
    }

    @Test
    void aggregate_honoursOrderConfig() {
        deskManifest("desk", Map.of("order", List.of("kanban")));
        when(documentService.listByKind("t", "p", "application")).thenReturn(List.of(
                appDoc("desk/aaa/_app.yaml", "workbook"),
                appDoc("desk/zzz/_app.yaml", "kanban")));

        DesktopView view = service.aggregate("t", "p", "desk", null);

        // kanban first despite the "zzz" folder sorting after "aaa".
        assertThat(view.getCards()).extracting(DesktopCard::getApp)
                .containsExactly("kanban", "workbook");
    }

    // ── Fixtures ──────────────────────────────────────────────────

    private void deskManifest(String folder, Map<String, Object> desktopBlock) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("common-desktop", desktopBlock);
        ApplicationDocument manifest = new ApplicationDocument(
                "application", "common-desktop", null, null, config, new LinkedHashMap<>());
        DocumentDocument doc = appDocWithBody(folder + "/_app.yaml",
                ApplicationCodec.serialize(manifest, YAML));
        when(documentService.findByPath("t", "p", folder + "/_app.yaml"))
                .thenReturn(Optional.of(doc));
    }

    private DocumentDocument appDoc(String path, String appType) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(appType, new LinkedHashMap<>());
        ApplicationDocument manifest = new ApplicationDocument(
                "application", appType, null, null, config, new LinkedHashMap<>());
        return appDocWithBody(path, ApplicationCodec.serialize(manifest, YAML));
    }

    private DocumentDocument appDocWithBody(String path, String body) {
        DocumentDocument doc = DocumentDocument.builder()
                .path(path).mimeType(YAML).build();
        bodies.put(path, body);
        return doc;
    }
}
