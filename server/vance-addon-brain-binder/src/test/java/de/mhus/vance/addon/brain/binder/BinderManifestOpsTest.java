package de.mhus.vance.addon.brain.binder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BinderManifestOpsTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";
    private static final String FOLDER = "binders/x";
    private static final String MANIFEST_PATH = "binders/x/_app.yaml";

    DocumentService documentService;
    SecurityContextFactory contextFactory;
    DocumentDocument manifestDoc;

    BinderManifestOps ops;
    private String manifestBody;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        contextFactory = mock(SecurityContextFactory.class);
        manifestDoc = mock(DocumentDocument.class);
        BinderResolver resolver = new BinderResolver(documentService);
        ops = new BinderManifestOps(documentService, resolver, contextFactory);

        lenient().when(manifestDoc.getId()).thenReturn("m1");
        lenient().when(manifestDoc.getPath()).thenReturn(MANIFEST_PATH);
        lenient().when(manifestDoc.getTitle()).thenReturn("Binder X");
        lenient().when(manifestDoc.getTenantId()).thenReturn(TENANT);
        lenient().when(manifestDoc.getMimeType()).thenReturn("application/yaml");

        lenient().when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq(MANIFEST_PATH)))
                .thenReturn(Optional.of(manifestDoc));
        lenient().when(documentService.loadContent(manifestDoc))
                .thenAnswer(inv -> new ByteArrayInputStream(
                        manifestBody.getBytes(StandardCharsets.UTF_8)));
    }

    private void manifest(String binderBlock) {
        manifestBody = "$meta:\n  kind: application\n  app: binder\ntitle: Binder X\n" + binderBlock;
    }

    private DocumentDocument targetDoc(String path, String kind) {
        DocumentDocument d = mock(DocumentDocument.class);
        lenient().when(d.getPath()).thenReturn(path);
        lenient().when(d.getKind()).thenReturn(kind);
        lenient().when(d.getTitle()).thenReturn("Doc " + path);
        lenient().when(d.getMimeType()).thenReturn("application/yaml");
        return d;
    }

    private BinderConfig capturePersistedConfig() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService).update(eq("m1"), any(), any(), body.capture(), any(),
                any(), any(), any(), any(), any(), any());
        ApplicationDocument doc = ApplicationCodec.parse(body.getValue(), "application/yaml");
        return BinderConfig.from(doc);
    }

    @Test
    void addEntry_appendsResolvedRefWithSection() {
        manifest("binder:\n  entries: []\n  index:\n    outputPath: _index.md\n");
        DocumentDocument t = targetDoc("reports/q1.sheet.yaml", "sheet");
        when(documentService.findByPath(TENANT, PROJECT, "reports/q1.sheet.yaml"))
                .thenReturn(Optional.of(t));

        ops.addEntry(TENANT, PROJECT, FOLDER, "vance:/reports/q1.sheet.yaml", "Reports", null, null);

        BinderConfig cfg = capturePersistedConfig();
        assertThat(cfg.entries()).hasSize(1);
        assertThat(cfg.entries().get(0).ref()).contains("reports/q1.sheet.yaml");
        assertThat(cfg.entries().get(0).ref()).contains("kind=sheet");
        assertThat(cfg.entries().get(0).section()).isEqualTo("Reports");
    }

    @Test
    void addEntry_isIdempotentOnSamePath() {
        manifest("binder:\n  entries:\n    - ref: vance:/reports/q1.sheet.yaml?kind=sheet\n"
                + "  index:\n    outputPath: _index.md\n");
        DocumentDocument t = targetDoc("reports/q1.sheet.yaml", "sheet");
        when(documentService.findByPath(TENANT, PROJECT, "reports/q1.sheet.yaml"))
                .thenReturn(Optional.of(t));

        ops.addEntry(TENANT, PROJECT, FOLDER, "reports/q1.sheet.yaml", null, null, null);

        assertThat(capturePersistedConfig().entries()).hasSize(1);
    }

    @Test
    void removeEntry_dropsMatchingPath() {
        manifest("binder:\n  entries:\n    - ref: vance:/a.yaml?kind=sheet\n"
                + "    - ref: vance:/b.yaml?kind=sheet\n  index:\n    outputPath: _index.md\n");
        lenient().when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("a.yaml")))
                .thenReturn(Optional.empty());
        lenient().when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("b.yaml")))
                .thenReturn(Optional.empty());

        ops.removeEntry(TENANT, PROJECT, FOLDER, "vance:/a.yaml", null);

        BinderConfig cfg = capturePersistedConfig();
        assertThat(cfg.entries()).hasSize(1);
        assertThat(cfg.entries().get(0).ref()).contains("b.yaml");
    }

    @Test
    void reorder_appliesGivenOrder() {
        manifest("binder:\n  entries:\n    - ref: vance:/a.yaml?kind=sheet\n"
                + "    - ref: vance:/b.yaml?kind=sheet\n  index:\n    outputPath: _index.md\n");
        lenient().when(documentService.findByPath(eq(TENANT), eq(PROJECT), any()))
                .thenReturn(Optional.empty());
        lenient().when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq(MANIFEST_PATH)))
                .thenReturn(Optional.of(manifestDoc));

        ops.reorder(TENANT, PROJECT, FOLDER,
                List.of("vance:/b.yaml", "vance:/a.yaml"), null);

        BinderConfig cfg = capturePersistedConfig();
        assertThat(cfg.entries()).hasSize(2);
        assertThat(cfg.entries().get(0).ref()).contains("b.yaml");
        assertThat(cfg.entries().get(1).ref()).contains("a.yaml");
    }
}
