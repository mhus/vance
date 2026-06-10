package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Surface-level coverage for {@link DocInfoTool}. Identity/shape
 * mapping, the "cached summary, no lazy-gen" promise, and the
 * stats counters for inline-text documents — the parts a future
 * refactor could quietly break without anything else noticing.
 */
class DocInfoToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj", "sess", "proc", "user");

    private DocumentService documentService;
    private EddieContext eddieContext;
    private DocInfoTool tool;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        eddieContext = mock(EddieContext.class);
        tool = new DocInfoTool(eddieContext, documentService);
    }

    @Test
    void invoke_byPath_returnsMetadata_withoutLoadingContent() {
        ProjectDocument project = new ProjectDocument();
        project.setName("proj");
        when(eddieContext.resolveProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CTX),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(project);

        DocumentDocument doc = inlineDoc(
                "doc-1", "documents/notes/ch1.md", "ch1.md",
                "Chapter 1", "text/markdown", 1234L, "# Chapter 1\nbody\n");
        doc.setKind("note");
        doc.setTags(List.of("draft"));
        doc.setHeaders(new LinkedHashMap<>(Map.of("kind", "note")));
        doc.setStatus(DocumentStatus.ACTIVE);
        doc.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        doc.setCreatedBy("alice");
        doc.setVersion(3L);
        doc.setLineageId("lin-1");
        doc.setSummary("Short recap.");
        doc.setSummarizedAt(Instant.parse("2026-01-02T00:00:00Z"));
        doc.setAutoSummary(true);
        when(documentService.findByPath("acme", "proj", "documents/notes/ch1.md"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "documents/notes/ch1.md"), CTX);

        assertThat(out)
                .containsEntry("id", "doc-1")
                .containsEntry("projectId", "proj")
                .containsEntry("path", "documents/notes/ch1.md")
                .containsEntry("name", "ch1.md")
                .containsEntry("title", "Chapter 1")
                .containsEntry("mimeType", "text/markdown")
                .containsEntry("kind", "note")
                .containsEntry("size", 1234L)
                .containsEntry("inline", true)
                .containsEntry("status", "ACTIVE")
                .containsEntry("createdBy", "alice")
                .containsEntry("version", 3L)
                .containsEntry("lineageId", "lin-1")
                .containsEntry("summary", "Short recap.")
                .containsEntry("autoSummary", true);
        assertThat(out).doesNotContainKey("charCount");
        assertThat(out).doesNotContainKey("wordCount");
        assertThat(out).doesNotContainKey("content");
    }

    @Test
    void invoke_withStats_inlineText_countsLocally() {
        ProjectDocument project = new ProjectDocument();
        project.setName("proj");
        when(eddieContext.resolveProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CTX),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(project);

        DocumentDocument doc = inlineDoc(
                "doc-2", "documents/n.md", "n.md", null,
                "text/markdown", 0L, "line one\nline two has four words\n");
        when(documentService.findByPath("acme", "proj", "documents/n.md"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "documents/n.md", "stats", true), CTX);

        assertThat(out)
                .containsEntry("charCount", "line one\nline two has four words\n".length())
                .containsEntry("wordCount", 7)
                .containsEntry("lineCount", 2);
    }

    @Test
    void invoke_byId_rejectsCrossTenant() {
        DocumentDocument doc = inlineDoc(
                "doc-3", "documents/x.md", "x.md", null, "text/markdown",
                0L, "body");
        doc.setTenantId("other-tenant");
        when(documentService.findById("doc-3")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> tool.invoke(Map.of("id", "doc-3"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not in your tenant");
    }

    @Test
    void invoke_neitherPathNorId_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'path' or 'id'");
    }

    @Test
    void invoke_emptyDocument_statsAllZero() {
        ProjectDocument project = new ProjectDocument();
        project.setName("proj");
        when(eddieContext.resolveProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CTX),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(project);

        DocumentDocument doc = inlineDoc(
                "doc-4", "documents/empty.md", "empty.md", null,
                "text/markdown", 0L, "");
        when(documentService.findByPath("acme", "proj", "documents/empty.md"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "documents/empty.md", "stats", true), CTX);

        assertThat(out)
                .containsEntry("charCount", 0)
                .containsEntry("wordCount", 0)
                .containsEntry("lineCount", 0);
    }

    @Test
    void labels_marked_readOnly_andDocument() {
        // doc_info must survive label-stripped modes (EXPLORING/PLANNING)
        // where write tools are filtered out — pin the labels so a future
        // refactor cannot quietly demote it.
        assertThat(tool.labels()).contains("read-only", "document");
    }

    @Test
    void name_is_stable() {
        assertThat(tool.name()).isEqualTo("doc_info");
    }

    private DocumentDocument inlineDoc(
            String id, String path, String name, String title,
            String mime, long size, String inlineText) {
        DocumentDocument d = new DocumentDocument();
        d.setId(id);
        d.setTenantId("acme");
        d.setProjectId("proj");
        d.setPath(path);
        d.setName(name);
        d.setTitle(title);
        d.setMimeType(mime);
        d.setSize(size);
        d.setStorageId("blob-" + id);
        when(documentService.readContent(d)).thenReturn(inlineText);
        return d;
    }
}
