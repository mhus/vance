package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The file_find-parity filters on {@code doc_find} (planning/
 * doc-file-tool-parity.md §3.4): path glob, size range, creation-time
 * range, sort key — all metadata-only, AND-combined with the classic
 * query substring. There is deliberately no modifiedAfter/Before: document
 * rows track createdAt but no modification timestamp.
 */
class DocFindToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private DocumentService documentService;
    private DocFindTool tool;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        EddieContext eddie = mock(EddieContext.class);
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("proj-a");
        when(eddie.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
        tool = new DocFindTool(eddie, documentService);
    }

    private DocumentDocument doc(String path, long size, Instant created) {
        DocumentDocument d = new DocumentDocument();
        d.setId("id-" + path);
        d.setPath(path);
        d.setName(path.substring(path.lastIndexOf('/') + 1));
        d.setSize(size);
        d.setCreatedAt(created);
        return d;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hits(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("hits");
    }

    private void projectHas(DocumentDocument... docs) {
        when(documentService.listByProject(anyString(), anyString())).thenReturn(List.of(docs));
    }

    @Test
    void queryOnly_stillWorks_andDefaultsToDocumentsPrefix() {
        projectHas(doc("documents/thesis.md", 10, null), doc("_vance/trash/thesis-old.md", 10, null));

        Map<String, Object> out = tool.invoke(new HashMap<>(Map.of("query", "thesis")), CTX);

        assertThat(hits(out)).hasSize(1);
        assertThat(hits(out).get(0)).containsEntry("path", "documents/thesis.md");
    }

    @Test
    void globOnly_matchesPathsRelativeToThePrefix() {
        projectHas(
                doc("documents/notes/a.md", 10, null),
                doc("documents/notes/b.txt", 10, null),
                doc("documents/top.md", 10, null));

        Map<String, Object> out = tool.invoke(new HashMap<>(Map.of("pathGlob", "**/*.md")), CTX);

        assertThat(hits(out))
                .extracting(r -> r.get("path"))
                .containsExactly("documents/notes/a.md", "documents/top.md");
    }

    @Test
    void queryAndGlob_combineWithAnd() {
        projectHas(doc("documents/notes/report-q3.md", 10, null), doc("documents/notes/report-q3-draft.txt", 10, null));

        Map<String, Object> out = tool.invoke(
                new HashMap<>(Map.of(
                        "query", "Q3",
                        "pathGlob", "**/*.md")),
                CTX);

        assertThat(hits(out)).extracting(r -> r.get("path")).containsExactly("documents/notes/report-q3.md");
    }

    @Test
    void sizeAndCreatedRanges_filterAndSort() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        projectHas(doc("documents/small-old.md", 10, t1), doc("documents/big-new.md", 500, t2));

        Map<String, Object> out = tool.invoke(
                new HashMap<>(Map.of(
                        "query", "md",
                        "minSizeBytes", 100,
                        "createdAfter", "2026-03-01T00:00:00Z",
                        "sortBy", "created")),
                CTX);

        assertThat(hits(out)).extracting(r -> r.get("path")).containsExactly("documents/big-new.md");
    }

    @Test
    void sortBySize_ordersDescending() {
        projectHas(doc("documents/a.md", 100, null), doc("documents/b.md", 900, null));

        Map<String, Object> out = tool.invoke(
                new HashMap<>(Map.of(
                        "query", "md",
                        "sortBy", "size")),
                CTX);

        assertThat(hits(out)).extracting(r -> r.get("path")).containsExactly("documents/b.md", "documents/a.md");
    }

    @Test
    void noCriteria_isRejected() {
        Map<String, Object> empty = new HashMap<>();

        assertThatThrownBy(() -> tool.invoke(empty, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Provide at least 'query' or 'pathGlob'");
    }

    @Test
    void malformedInstant_isALoudError() {
        Map<String, Object> p = new HashMap<>();
        p.put("query", "x");
        p.put("createdAfter", "yesterday");

        assertThatThrownBy(() -> tool.invoke(p, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'createdAfter' must be an ISO-8601 instant");
    }
}
