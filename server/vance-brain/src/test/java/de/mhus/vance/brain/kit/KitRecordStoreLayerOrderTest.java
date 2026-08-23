package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Layer order — which installed kit wins when two write the same artefact.
 *
 * <p>Two scales meet in one comparison: a hand-written {@code sortIndex} is a
 * small number, an install time is ~1.8e9 seconds. Compared directly, every
 * configured kit would rank <em>below</em> every unconfigured one, so setting
 * {@code sortIndex: 20} to win would lose. That is the arithmetic a test has
 * to nail down.
 *
 * <p>And the tolerance: the config document is the only kit file a human
 * edits by hand, so it is the likeliest to be malformed — and letting it throw
 * from here took {@code status}, {@code update-all} and {@code reapply-all}
 * down with an HTTP 500 for every <em>other</em> kit in the project.
 */
class KitRecordStoreLayerOrderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private DocumentService documentService;
    private KitRecordStore store;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        store = new KitRecordStore(documentService);
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void withoutConfig_theMostRecentInstallWins() {
        installed("old-aaaaaa", Instant.parse("2026-01-01T00:00:00Z"));
        installed("new-bbbbbb", Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(store.listInLayerOrder(TENANT, PROJECT))
                .extracting(KitInstalledRecordDto::getId)
                .containsExactly("old-aaaaaa", "new-bbbbbb");
    }

    @Test
    void anExplicitSortIndexBeatsEveryInstallTime() {
        // sortIndex 20 against an epoch second of ~1.8e9 — without the
        // explicit-rank floor the configured kit would sort first, i.e. lose.
        installed("old-aaaaaa", Instant.parse("2026-01-01T00:00:00Z"));
        installed("new-bbbbbb", Instant.parse("2026-06-01T00:00:00Z"));
        config("old-aaaaaa", KitConfigDto.builder().sortIndex(20).build());

        assertThat(store.listInLayerOrder(TENANT, PROJECT))
                .extracting(KitInstalledRecordDto::getId)
                .containsExactly("new-bbbbbb", "old-aaaaaa");
    }

    @Test
    void aMalformedConfigOrdersByInstallTimeInsteadOfFailingTheWholeList() {
        installed("old-aaaaaa", Instant.parse("2026-01-01T00:00:00Z"));
        installed("new-bbbbbb", Instant.parse("2026-06-01T00:00:00Z"));
        rawConfig("old-aaaaaa", "sortIndex: soon\n");

        assertThatCode(() -> store.listInLayerOrder(TENANT, PROJECT))
                .doesNotThrowAnyException();
        assertThat(store.listInLayerOrder(TENANT, PROJECT))
                .extracting(KitInstalledRecordDto::getId)
                .containsExactly("old-aaaaaa", "new-bbbbbb");
    }

    // ─── fixtures ───────────────────────────────────────────────────────

    private final List<DocumentDocument> records = new ArrayList<>();

    private void installed(String id, Instant installedAt) {
        KitInstalledRecordDto record = KitInstalledRecordDto.builder()
                .id(id)
                .kit(KitMetadataDto.builder().name(id).description("d").build())
                .origin(KitOriginDto.builder()
                        .url("https://git.example/" + id + ".git")
                        .installedAt(installedAt)
                        .build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(new ArrayList<>()).settings(new ArrayList<>()).build())
                .build();
        DocumentDocument doc = documentAt(KitRecordStore.recordPath(id));
        records.add(doc);
        when(documentService.readContent(doc))
                .thenReturn(KitYamlMapper.writeInstalledRecord(record));
        when(documentService.listUnderFolder(
                TENANT, PROJECT, KitRecordStore.INSTALLED_PREFIX))
                .thenReturn(new ArrayList<>(records));
    }

    private void config(String id, KitConfigDto config) {
        rawConfig(id, KitYamlMapper.writeConfig(config));
    }

    private void rawConfig(String id, String yaml) {
        String path = KitRecordStore.configPath(id);
        DocumentDocument doc = documentAt(path);
        when(documentService.findByPath(TENANT, PROJECT, path)).thenReturn(Optional.of(doc));
        when(documentService.readContent(eq(doc))).thenReturn(yaml);
    }

    private static DocumentDocument documentAt(String path) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT).path(path)
                .mimeType("application/yaml")
                .build();
        doc.setId("doc-" + path.hashCode());
        return doc;
    }
}
