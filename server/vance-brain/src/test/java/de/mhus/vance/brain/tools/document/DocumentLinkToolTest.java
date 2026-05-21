package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Surface tests for {@link DocumentLinkTool}: URI building, image-vs-link
 * syntax, cross-project handling, default-mode heuristic.
 */
class DocumentLinkToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private DocumentService documentService;
    private ProjectService projectService;
    private DocumentLinkTool tool;

    @BeforeEach
    void setUp() {
        documentService = Mockito.mock(DocumentService.class);
        projectService = Mockito.mock(ProjectService.class);
        tool = new DocumentLinkTool(projectService, documentService, new DocumentLinkBuilder());
    }

    @Test
    void invoke_sameProjectMindmap_buildsReferenceLink() {
        DocumentDocument doc = newDoc("proj-a", "documents/strategy.md", "mindmap", "Strategie");
        when(documentService.findByPath("acme", "proj-a", "documents/strategy.md"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "documents/strategy.md"), CTX);

        assertThat(out)
                .containsEntry("path", "documents/strategy.md")
                .containsEntry("kind", "mindmap")
                .containsEntry("project", null)
                .containsEntry("mode", "reference")
                .containsEntry("imageStyle", false);
        assertThat((String) out.get("markdownLink"))
                .isEqualTo("[Strategie](vance:/documents/strategy.md?kind=mindmap)");
    }

    @Test
    void invoke_imageKind_defaultsToImageSyntaxAndPreviewMode() {
        DocumentDocument doc = newDoc("proj-a", "diagrams/arch.png", "image", "Architektur");
        when(documentService.findByPath("acme", "proj-a", "diagrams/arch.png"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(Map.of("path", "diagrams/arch.png"), CTX);

        // Image kind → preview mode is default → mode segment omitted from URI
        assertThat((String) out.get("markdownLink"))
                .isEqualTo("![Architektur](vance:/diagrams/arch.png?kind=image)");
        assertThat(out).containsEntry("imageStyle", true).containsEntry("mode", "preview");
    }

    @Test
    void invoke_crossProject_addsAuthoritySegment() {
        DocumentDocument doc = newDoc("templates-shared", "reports/q.md", "markdown", "Template");
        when(projectService.findByTenantAndName("acme", "templates-shared"))
                .thenReturn(Optional.of(newProject("templates-shared")));
        when(documentService.findByPath("acme", "templates-shared", "reports/q.md"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "reports/q.md", "project", "templates-shared"), CTX);

        assertThat((String) out.get("markdownLink"))
                .isEqualTo("[Template](vance://templates-shared/reports/q.md?kind=markdown)");
        assertThat(out).containsEntry("project", "templates-shared");
    }

    @Test
    void invoke_textOverride_winsOverTitle() {
        DocumentDocument doc = newDoc("proj-a", "x.md", "markdown", "OriginalTitle");
        when(documentService.findByPath(eq("acme"), eq("proj-a"), eq("x.md")))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "x.md", "text", "Custom Label"), CTX);

        assertThat((String) out.get("markdownLink"))
                .startsWith("[Custom Label](");
    }

    @Test
    void invoke_modeOverride_addsModeSegment() {
        DocumentDocument doc = newDoc("proj-a", "x.png", "image", null);
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "x.png", "mode", "reference"), CTX);

        // image default is preview — explicit 'reference' must serialise
        assertThat((String) out.get("markdownLink"))
                .contains("kind=image")
                .contains("mode=reference");
    }

    @Test
    void invoke_pathNotFound_throwsToolException() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.invoke(Map.of("path", "missing.md"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("DOCUMENT_NOT_FOUND");
    }

    @Test
    void invoke_crossProjectNotInTenant_throwsToolException() {
        when(projectService.findByTenantAndName(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.invoke(
                Map.of("path", "x.md", "project", "ghost"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("CROSS_PROJECT_NOT_IN_TENANT");
    }

    @Test
    void invoke_neitherPathNorId_throwsToolException() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("path");
    }

    @Test
    void invoke_byId_resolvesAcrossProjects() {
        DocumentDocument doc = newDoc("proj-other", "shared/spec.md", "markdown", "Spec");
        doc.setId("507f1f77bcf86cd799439011");
        doc.setTenantId("acme");
        when(documentService.findById("507f1f77bcf86cd799439011"))
                .thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("id", "507f1f77bcf86cd799439011"), CTX);

        // ctx project is proj-a, doc project is proj-other → cross-project
        assertThat((String) out.get("markdownLink"))
                .startsWith("[Spec](vance://proj-other/shared/spec.md");
        assertThat(out).containsEntry("project", "proj-other");
    }

    @Test
    void invoke_pathWithSpaces_percentEncodesSegments() {
        DocumentDocument doc = newDoc("proj-a", "documents/Quartalsbericht Q1.pdf", "pdf", "Q1");
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));

        Map<String, Object> out = tool.invoke(
                Map.of("path", "documents/Quartalsbericht Q1.pdf"), CTX);

        assertThat((String) out.get("markdownLink"))
                .contains("documents/Quartalsbericht%20Q1.pdf");
    }

    private static DocumentDocument newDoc(
            String projectId, String path, String kind, String title) {
        DocumentDocument d = new DocumentDocument();
        d.setTenantId("acme");
        d.setProjectId(projectId);
        d.setPath(path);
        d.setKind(kind);
        d.setTitle(title);
        return d;
    }

    private static ProjectDocument newProject(String name) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        p.setTenantId("acme");
        return p;
    }
}
