package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.document.DocReadTool;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.KindRegistry;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * If-Match (contentHash) protocol on the doc_* family — the twin of the
 * file_* protocol from {@code specification/public/work-target.md}:
 *
 * <ul>
 *   <li>{@code doc_read} / {@code doc_read_lines} return a SHA-256 over the
 *       FULL body, independent of the served window.</li>
 *   <li>{@code doc_edit} / {@code doc_append} / {@code doc_replace_lines} /
 *       {@code doc_write} refuse on {@code expectedContentHash} mismatch —
 *       the hash check runs BEFORE any content matching, so a stale read
 *       produces the "read again" advice, not a content-mismatch hint.</li>
 *   <li>{@code doc_write} with a hash on a vanished path refuses — no silent
 *       resurrect.</li>
 *   <li>Successful writes return the new hash so consecutive edits chain
 *       without re-reading.</li>
 * </ul>
 */
class DocContentHashGuardTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private KindToolSupport support;
    private DocumentDocument doc;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        support = mock(KindToolSupport.class);
        documentService = mock(DocumentService.class);
        when(support.documentService()).thenReturn(documentService);

        doc = new DocumentDocument();
        doc.setId("doc-1");
        doc.setTenantId("acme");
        doc.setProjectId("proj-a");
        doc.setPath("notes/x.md");

        when(support.loadDocument(any(), any())).thenReturn(doc);
        when(support.requireInline(doc)).thenReturn(doc);
        when(support.identify(doc)).thenReturn("id=doc-1 path='notes/x.md'");
    }

    private void bodyIs(String body) {
        when(support.readBody(doc, CTX)).thenReturn(body);
    }

    // ── doc_edit ──────────────────────────────────────────────────────

    @Test
    void docEdit_staleHash_refusesBeforeSnippetMatch() {
        // The body drifted AND the snippet is gone — the error must be about
        // the hash (read again), not about the missing snippet (expand it).
        bodyIs("completely rewritten body");
        Map<String, Object> p = editParams("oldText", "newText");
        p.put("expectedContentHash", ContentHashes.sha256Hex("old body\noldText\nmore"));

        assertThatThrownBy(() -> new DocEditTool(support).invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contentHash mismatch")
                .hasMessageContaining("read the document again");

        verify(support, never()).writeBody(any(), any(), any());
    }

    @Test
    void docEdit_matchingHash_appliesAndReturnsChainableHash() {
        String body = "alpha\nbeta\ngamma";
        bodyIs(body);
        Map<String, Object> p = editParams("beta", "BETA");
        p.put("expectedContentHash", ContentHashes.sha256Hex(body));

        Map<String, Object> out = new DocEditTool(support).invoke(p, CTX);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        String updated = "alpha\nBETA\ngamma";
        assertThat(written.getValue()).isEqualTo(updated);
        assertThat(out.get("contentHash")).isEqualTo(ContentHashes.sha256Hex(updated));
    }

    @Test
    void docEdit_blankHash_isRejectedNotIgnored() {
        bodyIs("body");
        Map<String, Object> p = editParams("body", "new body");
        p.put("expectedContentHash", "  ");

        assertThatThrownBy(() -> new DocEditTool(support).invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'expectedContentHash' must be a non-empty string");
    }

    @Test
    void docEdit_absentHash_keepsOldBehaviour() {
        bodyIs("alpha");
        new DocEditTool(support).invoke(editParams("alpha", "beta"), CTX);
        verify(support).writeBody(any(), any(), any());
    }

    // ── doc_append / doc_replace_lines ────────────────────────────────

    @Test
    void docAppend_staleHash_refusesInsteadOfMergingOntoDrift() {
        bodyIs("current");
        Map<String, Object> p = new HashMap<>();
        p.put("content", "more");
        p.put("expectedContentHash", ContentHashes.sha256Hex("stale"));

        assertThatThrownBy(() -> new DocAppendTool(support).invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contentHash mismatch");

        verify(support, never()).writeBody(any(), any(), any());
    }

    @Test
    void docReplaceLines_staleHash_winsOverLineAnchorAdvice() {
        bodyIs("a\nb\nc");
        Map<String, Object> p = new HashMap<>();
        p.put("fromLine", 2);
        p.put("toLine", 2);
        p.put("newContent", "X");
        // Stale hash AND stale line anchors: the hash is the stronger
        // signal and must shape the advice.
        p.put("expectedFirstLine", "TOTALLY-DIFFERENT");
        p.put("expectedContentHash", ContentHashes.sha256Hex("stale"));

        assertThatThrownBy(() -> new DocReplaceLinesTool(support).invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contentHash mismatch")
                .hasMessageContaining("read the document again");

        verify(support, never()).writeBody(any(), any(), any());
    }

    // ── doc_write ─────────────────────────────────────────────────────

    @Test
    void docWrite_hashOnVanishedPath_refusesNoSilentResurrect() {
        DocWriteTool tool = writeTool();
        when(documentService.findByPath(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        Map<String, Object> p = writeParams();
        p.put("expectedContentHash", ContentHashes.sha256Hex("used to exist"));

        assertThatThrownBy(() -> tool.invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no document exists at 'notes/x.md'")
                .hasMessageContaining("Omit expectedContentHash");

        verify(documentService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void docWrite_staleHashOnExistingDoc_refusesOverwrite() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        existing.setPath("notes/x.md");
        when(documentService.findByPath(anyString(), anyString(), anyString())).thenReturn(Optional.of(existing));
        when(support.readBody(existing, CTX)).thenReturn("current on disk");

        DocWriteTool tool = writeTool();
        Map<String, Object> p = writeParams();
        p.put("expectedContentHash", ContentHashes.sha256Hex("stale read"));

        assertThatThrownBy(() -> tool.invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contentHash mismatch");

        verify(documentService, never())
                .update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── read side ─────────────────────────────────────────────────────

    @Test
    void docReadLines_hashCoversTheFullBody_notTheServedWindow() {
        String body = "l1\nl2\nl3\nl4\nl5";
        bodyIs(body);
        Map<String, Object> p = new HashMap<>();
        p.put("id", "doc-1");
        p.put("startLine", 2);
        p.put("maxLines", 1);

        Map<String, Object> out = new DocReadLinesTool(support).invoke(p, CTX);

        assertThat(out.get("contentHash")).isEqualTo(ContentHashes.sha256Hex(body));
    }

    @Test
    void docRead_returnsFullBodyHashEvenWhenTruncated() {
        DocumentDocument longDoc = new DocumentDocument();
        longDoc.setId("doc-1");
        longDoc.setTenantId("acme");
        longDoc.setPath("notes/x.md");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60_000; i++) sb.append('x');
        String body = sb.toString();
        when(documentService.findById("doc-1")).thenReturn(Optional.of(longDoc));
        when(documentService.readContent(longDoc)).thenReturn(body);

        Map<String, Object> p = new HashMap<>();
        p.put("id", "doc-1");

        DocReadTool tool = new DocReadTool(eddieContextMock(), documentService);
        Map<String, Object> out = tool.invoke(p, CTX);

        assertThat((Boolean) out.get("truncated")).isTrue();
        assertThat(out.get("contentHash")).isEqualTo(ContentHashes.sha256Hex(body));
    }

    // ── fixtures ──────────────────────────────────────────────────────

    private static Map<String, Object> editParams(String oldText, String newText) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", "doc-1");
        p.put("oldText", oldText);
        p.put("newText", newText);
        return p;
    }

    private static Map<String, Object> writeParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("path", "notes/x.md");
        p.put("content", "fresh content");
        return p;
    }

    private DocWriteTool writeTool() {
        EddieContext eddie = eddieContextMock();
        return new DocWriteTool(
                support, mock(KindResolver.class), mock(KindRegistry.class), mock(DocumentLinkBuilder.class));
    }

    private EddieContext eddieContextMock() {
        EddieContext eddie = mock(EddieContext.class);
        when(support.eddieContext()).thenReturn(eddie);
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("proj-a");
        when(eddie.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
        return eddie;
    }
}
