package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tool-shape tests for {@link DocWriteTextTool}. The interesting
 * behavioural difference vs. {@link DocCreateTextTool} is that the
 * write tool resolves to {@link DocumentService#upsertText} — which
 * never throws on a pre-existing path. These tests pin the params
 * passed to the service plus the response shape that Slart's
 * persist-phase worker reads back.
 */
class DocWriteTextToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "instant-hole", "sess", "proc", "user");

    private EddieContext eddieContext;
    private DocumentService documentService;
    private DocWriteTextTool tool;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        documentService = mock(DocumentService.class);
        tool = new DocWriteTextTool(eddieContext, documentService);

        ProjectDocument project = new ProjectDocument();
        project.setName("instant-hole");
        when(eddieContext.resolveProject(any(), eq(CTX), anyBoolean()))
                .thenReturn(project);
    }

    @Test
    void invoke_callsUpsertText_withResolvedProjectAndParams() {
        when(documentService.upsertText(
                eq("acme"), eq("instant-hole"),
                eq("essay/final-essay.md"),
                eq("Final essay"),
                eq(List.of("essay", "v1")),
                eq("Body text."),
                eq("user")))
                .thenReturn(doc(
                        "doc-1", "essay/final-essay.md",
                        "final-essay.md", "Final essay", 10L,
                        List.of("essay", "v1")));

        Map<String, Object> out = tool.invoke(Map.of(
                "path", "essay/final-essay.md",
                "title", "Final essay",
                "tags", List.of("essay", "v1"),
                "content", "Body text."), CTX);

        assertThat(out)
                .containsEntry("id", "doc-1")
                .containsEntry("path", "essay/final-essay.md")
                .containsEntry("name", "final-essay.md")
                .containsEntry("title", "Final essay")
                .containsEntry("size", 10L)
                .containsEntry("tags", List.of("essay", "v1"));
    }

    @Test
    void invoke_returnsSameShape_whenServiceOverwritesExisting() {
        // upsertText short-circuits to update(...) when the path
        // exists; the tool just propagates the returned document.
        // No exception bubbles up (this is the whole reason the
        // tool exists vs. doc_create_text).
        when(documentService.upsertText(
                any(), any(), eq("essay/final-essay.md"),
                any(), any(), eq("New body."), any()))
                .thenReturn(doc(
                        "doc-1", "essay/final-essay.md",
                        "final-essay.md", null, 9L, null));

        Map<String, Object> out = tool.invoke(Map.of(
                "path", "essay/final-essay.md",
                "content", "New body."), CTX);

        assertThat(out)
                .containsEntry("id", "doc-1")
                .containsEntry("path", "essay/final-essay.md")
                .doesNotContainKey("title")
                .doesNotContainKey("tags");
    }

    @Test
    void invoke_throws_whenContentMissing() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tool.invoke(Map.of(
                        "path", "essay/final-essay.md"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("content");
    }

    @Test
    void invoke_autoGeneratesPath_whenOmitted() {
        // Without path, the tool delegates to DocCreateTextTool.autoPath
        // (same slug rules). Verify the slug-based path ends up at
        // the service call — exact form doesn't matter, only that
        // it's under 'documents/' and ends in '.md'.
        when(documentService.upsertText(
                eq("acme"), eq("instant-hole"),
                any(), eq("My note"), isNull(),
                eq("hi"), eq("user")))
                .thenReturn(doc("d", "documents/my-note.md",
                        "my-note.md", "My note", 2L, null));

        Map<String, Object> out = tool.invoke(Map.of(
                "title", "My note",
                "content", "hi"), CTX);

        assertThat(out).containsEntry("path", "documents/my-note.md");
        verify(documentService).upsertText(
                eq("acme"), eq("instant-hole"),
                eq("documents/my-note.md"),
                eq("My note"), isNull(),
                eq("hi"), eq("user"));
    }

    @Test
    void labels_include_write_and_document() {
        // Important for Slart's recovery loops: persist phases run
        // in workers that may filter by label, and 'write' is the
        // marker that survives EXPLORING/PLANNING strips. 'document'
        // tags the tool family alongside doc_summary / doc_info /
        // doc_read.
        assertThat(tool.labels()).contains("write", "document");
    }

    @Test
    void name_is_stable_for_recipe_yaml_lookup() {
        // Hard-coded in ValidatingPhase.WRITE_TOOL_NAMES and in the
        // writing.yaml persist-pattern; a rename here silently breaks
        // both the validator and Slart's emitted recipes.
        assertThat(tool.name()).isEqualTo("doc_write_text");
    }

    private static DocumentDocument doc(
            String id, String path, String name,
            @org.jspecify.annotations.Nullable String title, long size,
            @org.jspecify.annotations.Nullable List<String> tags) {
        DocumentDocument d = new DocumentDocument();
        d.setId(id);
        d.setProjectId("instant-hole");
        d.setPath(path);
        d.setName(name);
        d.setTitle(title);
        d.setSize(size);
        d.setTags(tags);
        return d;
    }
}
