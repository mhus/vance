package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DesignerFolderReaderTest {

    private DocumentService documentService;
    private DesignerFolderReader reader;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        reader = new DesignerFolderReader(documentService);
    }

    private DocumentDocument doc(String path) {
        DocumentDocument d = new DocumentDocument();
        d.setPath(path);
        d.setMimeType("text/markdown");
        return d;
    }

    private DocumentDocument doc(String path, String mimeType) {
        DocumentDocument d = doc(path);
        d.setMimeType(mimeType);
        return d;
    }

    private void givenDocs(DocumentDocument... docs) {
        when(documentService.listUnderFolder("acme", "web", "designs/")).thenReturn(List.of(docs));
    }

    @Test
    void scan_groupsFilesByDesignAndSorts() {
        givenDocs(
                doc("designs/_app.yaml"),
                doc("designs/landing/index.html"),
                doc("designs/landing/style.css"),
                doc("designs/dashboard/index.html"),
                doc("designs/landing/assets/logo.png"));

        DesignerFolderReader.Scan scan = reader.scan("acme", "web", "designs");

        assertThat(scan.designs())
                .extracting(DesignerFolderReader.DesignScan::name)
                .containsExactly("dashboard", "landing");
        DesignerFolderReader.DesignScan landing = scan.find("landing").orElseThrow();
        assertThat(landing.files()).containsExactly("assets/logo.png", "index.html", "style.css");
        assertThat(landing.fileCount()).isEqualTo(3);
    }

    @Test
    void scan_ignoresFoldersWithoutEntryFile() {
        givenDocs(doc("designs/notes/some.txt"), doc("designs/real/index.html"));

        DesignerFolderReader.Scan scan = reader.scan("acme", "web", "designs");

        assertThat(scan.designs()).hasSize(1);
        assertThat(scan.find("real")).isPresent();
    }

    @Test
    void scan_readsDesignYamlMetadata() {
        givenDocs(doc("designs/landing/index.html"), doc("designs/landing/design.yaml"));
        when(documentService.readContent(doc("designs/landing/design.yaml")))
                .thenReturn("title: Landing Page\ndescription: The new marketing page\n");

        DesignerFolderReader.Scan scan = reader.scan("acme", "web", "designs");

        DesignerFolderReader.DesignScan landing = scan.find("landing").orElseThrow();
        assertThat(landing.title()).isEqualTo("Landing Page");
        assertThat(landing.description()).isEqualTo("The new marketing page");
        assertThat(landing.displayTitle()).isEqualTo("Landing Page");
        // The metadata file is not a design file.
        assertThat(landing.files()).containsExactly("index.html");
        assertThat(landing.fileCount()).isEqualTo(1);
    }

    @Test
    void scan_brokenDesignYamlDoesNotHideTheDesign() {
        givenDocs(doc("designs/landing/index.html"), doc("designs/landing/design.yaml"));
        when(documentService.readContent(doc("designs/landing/design.yaml"))).thenReturn("title: [unclosed");

        DesignerFolderReader.Scan scan = reader.scan("acme", "web", "designs");

        assertThat(scan.find("landing")).isPresent();
        assertThat(scan.find("landing").orElseThrow().title()).isNull();
        // Falls back to the folder name.
        assertThat(scan.find("landing").orElseThrow().displayTitle()).isEqualTo("landing");
    }

    @Test
    void scan_ignoresReservedAndLooseFiles() {
        givenDocs(
                doc("designs/_app.yaml"),
                doc("designs/_index.md"),
                doc("designs/readme.txt"),
                doc("designs/_drafts/index.html"));

        assertThat(reader.scan("acme", "web", "designs").designs()).isEmpty();
    }

    @Test
    void scan_appliesManifestOrderWithAlphabeticalTail() {
        givenDocs(doc("designs/alpha/index.html"), doc("designs/bravo/index.html"), doc("designs/charlie/index.html"));
        DocumentDocument manifest = doc("designs/_app.yaml");
        manifest.setMimeType("application/yaml");
        when(documentService.findByPath("acme", "web", "designs/_app.yaml"))
                .thenReturn(java.util.Optional.of(manifest));
        when(documentService.readContent(manifest))
                .thenReturn("$meta:\n  kind: application\n  app: designer\ndesigner:\n  order: [bravo, charlie]\n");

        List<String> names = reader.scan("acme", "web", "designs").designs().stream()
                .map(DesignerFolderReader.DesignScan::name)
                .toList();

        // Listed designs in manifest order, the rest alphabetical.
        assertThat(names).containsExactly("bravo", "charlie", "alpha");
    }

    @Test
    void scan_brokenManifestFallsBackToAlphabetical() {
        givenDocs(doc("designs/alpha/index.html"), doc("designs/bravo/index.html"));
        DocumentDocument manifest = doc("designs/_app.yaml");
        manifest.setMimeType("application/yaml");
        when(documentService.findByPath("acme", "web", "designs/_app.yaml"))
                .thenReturn(java.util.Optional.of(manifest));
        when(documentService.readContent(manifest)).thenReturn(":: not yaml [");

        List<String> names = reader.scan("acme", "web", "designs").designs().stream()
                .map(DesignerFolderReader.DesignScan::name)
                .toList();

        assertThat(names).containsExactly("alpha", "bravo");
    }

    @Test
    void scan_carriesStoredMimePerFile() {
        givenDocs(
                doc("designs/_app.yaml"),
                doc("designs/landing/index.html", "text/html"),
                doc("designs/landing/style.css", "text/css"));

        DesignerFolderReader.DesignScan landing =
                reader.scan("acme", "web", "designs").find("landing").orElseThrow();

        assertThat(landing.mimeByFile())
                .containsEntry("index.html", "text/html")
                .containsEntry("style.css", "text/css");
    }

    @Test
    void scan_emptyFolderYieldsEmptyList() {
        givenDocs();

        assertThat(reader.scan("acme", "web", "designs").designs()).isEmpty();
    }

    @Test
    void scan_resultIsUsableAsStream() {
        // The scan result feeds the view endpoint (mapped) and refresh
        // (rendered) — a plain immutable list keeps both consumers safe.
        givenDocs(doc("designs/landing/index.html"));

        List<String> names = reader.scan("acme", "web", "designs").designs().stream()
                .map(DesignerFolderReader.DesignScan::name)
                .toList();

        assertThat(names).containsExactly("landing");
    }
}
