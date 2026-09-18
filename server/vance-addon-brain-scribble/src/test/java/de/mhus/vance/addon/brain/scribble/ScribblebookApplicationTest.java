package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScribblebookApplicationTest {

    private static final String YAML = "application/yaml";

    private DocumentService documentService;
    private SecurityContextFactory contextFactory;
    private ScribblebookApplication application;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        contextFactory = mock(SecurityContextFactory.class);
        ScribblebookFolderReader folderReader = new ScribblebookFolderReader(documentService);
        ScribbleService scribbleService = new ScribbleService(documentService, contextFactory);
        DocumentLinkBuilder linkBuilder = mock(DocumentLinkBuilder.class);
        when(linkBuilder.linkFor(any(), any())).thenReturn("vance:/link");
        application = new ScribblebookApplication(
                folderReader, scribbleService, documentService, linkBuilder, contextFactory);
    }

    private DocumentDocument doc(String path, String title, String mime, String body) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id-" + path);
        when(doc.getPath()).thenReturn(path);
        when(doc.getTitle()).thenReturn(title);
        when(doc.getMimeType()).thenReturn(mime);
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return doc;
    }

    private void givenManifest(String folder, String title) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("index", Map.of("outputPath", "_index.md"));
        ApplicationDocument manifest = new ApplicationDocument(
                "application",
                ScribblebookApplication.APP_NAME,
                title,
                null,
                Map.of(ScribblebookApplication.APP_NAME, block),
                new LinkedHashMap<>());
        DocumentDocument manifestDoc =
                doc(folder + "/_app.yaml", title, YAML, ApplicationCodec.serialize(manifest, YAML));
        when(documentService.findByPath("t", "p", folder + "/_app.yaml")).thenReturn(Optional.of(manifestDoc));
    }

    @Test
    void refresh_writesIndexWithSheetsSortedAndExcludesForeignFiles() {
        givenManifest("notizbuch", "Mein Notizbuch");
        // Two sheets in the folder (deliberately unsorted titles), one foreign
        // sheet outside the folder, one underscore system file inside it. Built
        // BEFORE the listByKind stub — doc() itself stubs loadContent, and a
        // when() inside another when()'s argument is unfinished stubbing.
        DocumentDocument zettelDoc = doc("notizbuch/zettel.scribble.yaml", "Zettel", YAML, "");
        DocumentDocument ideenDoc = doc("notizbuch/ideen.scribble.yaml", "Ideen", YAML, "");
        DocumentDocument woandersDoc = doc("sonstiges/woanders.scribble.yaml", "Woanders", YAML, "");
        DocumentDocument mistDoc = doc("notizbuch/_alter-mist.scribble.yaml", "Mist", YAML, "");
        when(documentService.listByKind("t", "p", "scribble"))
                .thenReturn(List.of(zettelDoc, ideenDoc, woandersDoc, mistDoc));
        when(documentService.findByPath("t", "p", "notizbuch/_index.md")).thenReturn(Optional.empty());
        DocumentDocument indexDoc = mock(DocumentDocument.class);
        when(indexDoc.getPath()).thenReturn("notizbuch/_index.md");
        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        when(documentService.create(any(), any(), any(), any(), any(), any(), body.capture(), any(), any()))
                .thenReturn(indexDoc);

        VanceApplication.RefreshResult result =
                application.refresh(new VanceApplication.RefreshContext("t", "p", "notizbuch", "alice", null));

        assertThat(result.folder()).isEqualTo("notizbuch");
        assertThat(result.artefacts()).hasSize(1);
        assertThat(result.artefacts().get(0).path()).isEqualTo("notizbuch/_index.md");

        String index = new String(readAll(body.getValue()), StandardCharsets.UTF_8);
        // Sorted by title, foreign folder and _-prefixed system files excluded.
        int ideen = index.indexOf("Ideen](ideen.scribble.yaml)");
        int zettel = index.indexOf("Zettel](zettel.scribble.yaml)");
        assertThat(ideen).isGreaterThan(0);
        assertThat(zettel).isGreaterThan(ideen);
        assertThat(index).doesNotContain("Woanders");
        assertThat(index).doesNotContain("_alter-mist");
        assertThat(index).contains("Mein Notizbuch");
    }

    @Test
    void refresh_emptyFolder_rendersEmptyHint() {
        givenManifest("leer", "Leer");
        when(documentService.listByKind("t", "p", "scribble")).thenReturn(List.of());
        when(documentService.findByPath("t", "p", "leer/_index.md")).thenReturn(Optional.empty());
        DocumentDocument indexDoc = mock(DocumentDocument.class);
        when(indexDoc.getPath()).thenReturn("leer/_index.md");
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(indexDoc);

        application.refresh(new VanceApplication.RefreshContext("t", "p", "leer", "alice", null));

        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(documentService)
                .create(any(), any(), eq("leer/_index.md"), any(), any(), any(), body.capture(), any(), any());
        String index = new String(readAll(body.getValue()), StandardCharsets.UTF_8);
        assertThat(index).contains("Noch keine Bl");
    }

    @Test
    void refresh_withoutManifestTitle_fallsBackToFolderLeaf() {
        givenManifest("notizen", null);
        when(documentService.listByKind("t", "p", "scribble")).thenReturn(List.of());
        when(documentService.findByPath("t", "p", "notizen/_index.md")).thenReturn(Optional.empty());
        DocumentDocument indexDoc = mock(DocumentDocument.class);
        when(indexDoc.getPath()).thenReturn("notizen/_index.md");
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(indexDoc);

        application.refresh(new VanceApplication.RefreshContext("t", "p", "notizen", "alice", null));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(documentService).create(any(), any(), any(), title.capture(), any(), any(), any(), any(), any());
        assertThat(title.getValue()).isEqualTo("Index — notizen");
    }

    @Test
    void promptInject_namesTheAppAndSaysInkIsUnreadable() {
        String inject = application.promptInject(
                new VanceApplication.PromptInjectContext("t", "p", "notizbuch", null, null, null));
        assertThat(inject).contains("scribblebook");
        assertThat(inject).contains("notizbuch");
        assertThat(inject).contains("scribble_validate");
        assertThat(inject).contains("NOT text");
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
