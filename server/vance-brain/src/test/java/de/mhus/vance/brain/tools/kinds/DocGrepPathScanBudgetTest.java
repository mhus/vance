package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The scan budget on {@code doc_grep_path} (planning/doc-file-tool-parity.md
 * §3.3): a whole-project scan is O(number of documents) storage fetches, so
 * the scan is bounded — maxScannedDocs with truncated+warning on stop,
 * oversized documents skipped via metadata (never read), mounted docs
 * excluded from a '*' scan, deterministic path order, and one body fetch
 * per scanned document.
 */
class DocGrepPathScanBudgetTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private KindToolSupport support;
    private DocGrepPathTool tool;
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

        tool = new DocGrepPathTool(support);
    }

    private DocumentDocument doc(String path, String body) {
        DocumentDocument d = new DocumentDocument();
        d.setId("id-" + path);
        d.setPath(path);
        d.setSize(body == null ? 0 : body.length());
        if (body != null) when(support.readBody(d, CTX)).thenReturn(body);
        return d;
    }

    private Map<String, Object> grep(String pattern, Object... extra) {
        Map<String, Object> p = new HashMap<>();
        p.put("pattern", pattern);
        for (int i = 0; i < extra.length; i += 2) p.put((String) extra[i], extra[i + 1]);
        return p;
    }

    private void projectHas(List<DocumentDocument> docs) {
        when(documentService.listByProject(anyString(), anyString())).thenReturn(docs);
    }

    @Test
    void maxScannedDocsCap_stopsEarlyWithWarning() {
        List<DocumentDocument> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) docs.add(doc("documents/d" + i + ".md", "needle\n"));
        projectHas(docs);

        Map<String, Object> out = tool.invoke(grep("needle", "maxScannedDocs", 2, "pathPrefix", "documents/"), CTX);

        assertThat(out.get("scannedDocuments")).isEqualTo(2);
        assertThat(out.get("truncated")).isEqualTo(true);
        assertThat((String) out.get("warning"))
                .contains("maxScannedDocs cap of 2")
                .contains("2 of 3 candidate documents")
                .contains("narrow the pathPrefix");
        // Path order is deterministic: d0, d1 scanned, d2 not.
        // Path order is deterministic: d0, d1 scanned, d2 never fetched.
        verify(support, never()).readBody(docs.get(2), CTX);
    }

    @Test
    void oversizedDoc_isSkippedWithoutBeingRead() {
        DocumentDocument huge = new DocumentDocument();
        huge.setId("id-huge");
        huge.setPath("documents/huge.md");
        huge.setSize(DocScanBudget.MAX_DOC_BYTES + 1);
        DocumentDocument normal = doc("documents/normal.md", "needle\n");

        projectHas(List.of(huge, normal));

        Map<String, Object> out = tool.invoke(grep("needle", "maxScannedDocs", 10), CTX);

        assertThat(out.get("skippedOversized")).isEqualTo(1);
        assertThat(out.get("scannedDocuments")).isEqualTo(1);
        assertThat(out.get("truncated")).isEqualTo(false);
        assertThat(out).doesNotContainKey("warning");
        // The oversized body is never fetched — the cap works on metadata.
        verify(support, never()).readBody(huge, CTX);
    }

    @Test
    void wholeProjectScan_excludesMountedDocs() {
        DocumentDocument mounted = doc("_ext/gitlib/readme.md", "needle\n");
        DocumentDocument stored = doc("documents/note.md", "needle\n");
        projectHas(List.of(mounted, stored));

        Map<String, Object> out = tool.invoke(grep("needle", "pathPrefix", "*"), CTX);

        verify(support, never()).readBody(mounted, CTX);
        assertThat(out.get("scannedDocuments")).isEqualTo(1);

        // An explicit _ext prefix scans mounted docs deliberately.
        Map<String, Object> ext = tool.invoke(grep("needle", "pathPrefix", "_ext/"), CTX);
        verify(support, times(1)).readBody(mounted, CTX);
        assertThat(ext.get("scannedDocuments")).isEqualTo(1);
    }

    @Test
    void candidatesAreScannedInPathOrder() {
        List<DocumentDocument> docs = List.of(
                doc("documents/c.md", "needle\n"),
                doc("documents/a.md", "needle\n"),
                doc("documents/b.md", "needle\n"));
        projectHas(new ArrayList<>(docs));

        tool.invoke(grep("needle", "maxScannedDocs", 2, "pathPrefix", "documents/"), CTX);

        InOrder inOrder = inOrder(support);
        inOrder.verify(support).readBody(docs.get(1), CTX); // a.md
        inOrder.verify(support).readBody(docs.get(2), CTX); // b.md
        verify(support, never()).readBody(docs.get(0), CTX); // c.md never reached
    }

    @Test
    void filesWithMatches_readsEachBodyOnce() {
        DocumentDocument d1 = doc("documents/one.md", "needle\n");
        DocumentDocument d2 = doc("documents/two.md", "nothing");
        projectHas(List.of(d1, d2));

        Map<String, Object> out = tool.invoke(grep("needle", "outputMode", "files_with_matches"), CTX);

        // This mode used to read every body twice (null-check + scan).
        verify(support, times(1)).readBody(d1, CTX);
        verify(support, times(1)).readBody(d2, CTX);
        assertThat(out.get("matchCount")).isEqualTo(1);
        assertThat(out.get("truncated")).isEqualTo(false);
        assertThat(out.get("scannedDocuments")).isEqualTo(2);
    }

    @Test
    void ageEncryptedDocs_areNeverFetched() {
        DocumentDocument encrypted = new DocumentDocument();
        encrypted.setId("id-age");
        encrypted.setPath("documents/secret.age");
        encrypted.setSize(100);
        encrypted.setMimeType("application/age+armored");
        DocumentDocument plain = doc("documents/plain.md", "needle\n");
        projectHas(List.of(encrypted, plain));

        Map<String, Object> out = tool.invoke(grep("needle"), CTX);

        verify(support, never()).readBody(encrypted, CTX);
        assertThat(out.get("scannedDocuments")).isEqualTo(1);
    }
}
