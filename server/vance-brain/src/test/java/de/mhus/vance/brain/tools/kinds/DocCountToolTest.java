package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocCountToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private KindToolSupport support;
    private DocCountTool tool;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        support = mock(KindToolSupport.class);
        documentService = mock(DocumentService.class);
        when(support.documentService()).thenReturn(documentService);

        EddieContext eddie = mock(EddieContext.class);
        when(support.eddieContext()).thenReturn(eddie);
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("proj-a");
        when(eddie.resolveProject(any(), any(), anyBoolean())).thenReturn(project);

        tool = new DocCountTool(support);
    }

    private DocumentDocument doc(String path, String body) {
        DocumentDocument d = new DocumentDocument();
        d.setId("id-" + path);
        d.setPath(path);
        d.setSize(body == null ? 0 : body.length());
        if (body != null) when(support.readBody(d, CTX)).thenReturn(body);
        return d;
    }

    @Test
    void singleDoc_countsLinesAndChars() {
        // Non-ASCII body with a diverging size metadata: bytes must come
        // from the counted body (UTF-8: é = 2 bytes), not from the row's
        // size field — lines/chars/bytes describe the same text.
        DocumentDocument d = doc("documents/a.md", "one\nhéllo");
        when(support.loadDocument(any(), any())).thenReturn(d);

        Map<String, Object> p = new HashMap<>();
        p.put("id", "id-documents/a.md");

        Map<String, Object> out = tool.invoke(p, CTX);

        assertThat(out.get("lines")).isEqualTo(2L);
        assertThat(out.get("chars")).isEqualTo((long) "one\nhéllo".length());
        assertThat(out.get("bytes"))
                .isEqualTo((long) "one\nhéllo".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void singleDoc_withPattern_countsOnlyMatchingLines() {
        DocumentDocument d = doc("documents/a.md", "alpha\nbeta\nalphabet");
        when(support.loadDocument(any(), any())).thenReturn(d);

        Map<String, Object> p = new HashMap<>();
        p.put("id", "id-documents/a.md");
        p.put("pattern", "alpha");

        Map<String, Object> out = tool.invoke(p, CTX);

        assertThat(out.get("lines")).isEqualTo(2L); // matching lines
        assertThat(out.get("totalLines")).isEqualTo(3L);
        assertThat(out.get("chars")).isEqualTo((long) ("alpha".length() + "alphabet".length()));
    }

    @Test
    void scanMode_aggregatesOverPrefixAndHonoursBudget() {
        List<DocumentDocument> docs =
                List.of(doc("documents/a.md", "x\nx"), doc("documents/b.md", "x"), doc("documents/c.md", "x"));
        when(documentService.listByProject(anyString(), anyString())).thenReturn(docs);

        Map<String, Object> p = new HashMap<>();
        p.put("maxScannedDocs", 2);

        Map<String, Object> out = tool.invoke(p, CTX);

        assertThat(out.get("scannedDocuments")).isEqualTo(2);
        assertThat(out.get("lines")).isEqualTo(3L); // a.md (2) + b.md (1), c.md not reached
        // Byte aggregate over the scanned docs' claimed sizes (a.md "x\nx" = 3, b.md = 1).
        assertThat(out.get("bytes")).isEqualTo(4L);
        assertThat(out.get("truncated")).isEqualTo(true);
        assertThat((String) out.get("warning")).contains("maxScannedDocs cap of 2");
        verify(support, never()).readBody(docs.get(2), CTX);
    }

    @Test
    void scanMode_excludesMountedOnWholeProject() {
        DocumentDocument mounted = doc("_ext/lib/x.md", "x");
        DocumentDocument stored = doc("documents/a.md", "x");
        when(documentService.listByProject(anyString(), anyString())).thenReturn(List.of(mounted, stored));

        Map<String, Object> out = tool.invoke(new HashMap<>(Map.of("pathPrefix", "*")), CTX);

        verify(support, never()).readBody(mounted, CTX);
        assertThat(out.get("scannedDocuments")).isEqualTo(1);
        assertThat(out.get("pathPrefix")).isEqualTo("*");
    }
}
