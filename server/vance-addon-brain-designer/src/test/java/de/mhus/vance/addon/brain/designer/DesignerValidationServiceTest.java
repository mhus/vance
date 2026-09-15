package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The validator's contract is to say out loud what the catalogue drops
 * silently — every finding below is a state the scan tolerates on
 * purpose.
 */
class DesignerValidationServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "web";

    private DocumentService documentService;
    private DesignerFolderReader reader;
    private DesignerValidationService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        reader = new DesignerFolderReader(documentService);
        service = new DesignerValidationService(reader);
        // No manifest anywhere unless a test says otherwise.
        when(documentService.findByPath(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
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

    private void givenManifestWithOrder(String... order) {
        DocumentDocument manifest = doc("designs/_app.yaml");
        manifest.setMimeType("application/yaml");
        when(documentService.findByPath(TENANT, PROJECT, "designs/_app.yaml")).thenReturn(Optional.of(manifest));
        StringBuilder body = new StringBuilder("$meta:\n  kind: application\n  app: designer\n");
        if (order.length > 0) {
            body.append("designer:\n  order: [")
                    .append(String.join(", ", order))
                    .append("]\n");
        }
        when(documentService.readContent(manifest)).thenReturn(body.toString());
    }

    private void givenDocs(DocumentDocument... docs) {
        when(documentService.listUnderFolder(TENANT, PROJECT, "designs/")).thenReturn(List.of(docs));
    }

    @Test
    void validate_cleanFolderIsOk() {
        givenManifestWithOrder();
        givenDocs(doc("designs/_app.yaml"), doc("designs/landing/index.html", "text/html"));

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void validate_missingManifestIsAnError() {
        givenDocs(doc("designs/landing/index.html", "text/html"));

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).extracting(Finding::code).containsExactly("missing-manifest");
    }

    @Test
    void validate_reportsFolderWithoutEntryFile() {
        givenManifestWithOrder();
        givenDocs(doc("designs/landing/index.html", "text/html"), doc("designs/forgotten/style.css", "text/css"));

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isTrue(); // warning, not error
        assertThat(result.findings()).extracting(Finding::code).containsExactly("missing-entry-file");
        assertThat(result.findings().get(0).message()).contains("index.html");
    }

    @Test
    void validate_reportsBrokenDesignYaml() {
        givenManifestWithOrder();
        DocumentDocument meta = doc("designs/landing/design.yaml");
        givenDocs(doc("designs/landing/index.html", "text/html"), meta);
        when(documentService.readContent(meta)).thenReturn("title: [broken");

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).extracting(Finding::code).containsExactly("broken-design-meta");
    }

    @Test
    void validate_reportsGhostOrderEntries() {
        givenManifestWithOrder("landing", "ghost");
        givenDocs(doc("designs/landing/index.html", "text/html"));

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isTrue(); // warning, not error
        assertThat(result.findings()).extracting(Finding::code).containsExactly("ghost-order-entry");
        assertThat(result.findings().get(0).message()).contains("ghost");
    }

    @Test
    void validate_reportsEntryFileWithWrongMimeAsError() {
        givenManifestWithOrder();
        // The bug an agent's doc_write used to produce: markdown stored on
        // an index.html — nosniff turns the preview into raw source.
        givenDocs(doc("designs/landing/index.html"), doc("designs/landing/style.css", "text/css"));

        DesignerValidationService.Result result = service.validate(TENANT, PROJECT, "designs");

        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).extracting(Finding::code).contains("mime-mismatch");
        assertThat(result.findings().get(0).message()).contains("text/html");
        assertThat(result.findings().get(0).message()).contains("mimeType");
    }

    @Test
    void validate_correctMimesReportNothing() {
        givenManifestWithOrder();
        givenDocs(doc("designs/landing/index.html", "text/html"), doc("designs/landing/style.css", "text/css"));

        assertThat(service.validate(TENANT, PROJECT, "designs").findings()).isEmpty();
    }

    @Test
    void validate_knownOrderEntriesDoNotReport() {
        givenManifestWithOrder("landing");
        givenDocs(doc("designs/landing/index.html", "text/html"));

        assertThat(service.validate(TENANT, PROJECT, "designs").findings()).isEmpty();
    }
}
