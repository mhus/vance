package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Verifies the {@code vance.documents.*} surface of {@link VanceScriptApi}
 * against a mocked {@link DocumentService}: scope cascading, error mapping,
 * idempotent writes and trash-folder protection.
 */
class VanceScriptApiDocumentsTest {

    private DocumentService documentService;
    private VanceScriptApi api;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        api = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), documentService);
    }

    @Test
    void read_existingDoc_returnsContent() {
        DocumentDocument doc = doc("notes/hello.md", "Hello world");
        when(documentService.findByPath("acme", "proj", "notes/hello.md"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("Hello world");

        String text = api.documents.read("notes/hello.md");

        assertThat(text).isEqualTo("Hello world");
    }

    @Test
    void read_missingDoc_throwsScriptHostException() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.documents.read("ghosts.md"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining("ghosts.md");
    }

    @Test
    void write_upsertsThroughDocumentService_withScopeUserAsCreator() {
        api.documents.write("notes/new.md", "# New");

        verify(documentService).upsertText(
                eq("acme"), eq("proj"), eq("notes/new.md"),
                eq(null), eq(null), eq("# New"), eq("alice"), any());
    }

    @Test
    void write_nullContent_throws() {
        assertThatThrownBy(() -> api.documents.write("notes/x.md", null))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("content");
        verify(documentService, never()).upsertText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void write_intoTrashFolder_isRejected() {
        assertThatThrownBy(() -> api.documents.write("_bin/sneaky.md", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("_bin/");
        verify(documentService, never()).upsertText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void exists_returnsTrueWhenServiceReportsPresent() {
        when(documentService.findByPath("acme", "proj", "a.md"))
                .thenReturn(Optional.of(doc("a.md", "x")));

        assertThat(api.documents.exists("a.md")).isTrue();
    }

    @Test
    void exists_returnsFalseOnMissing() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(api.documents.exists("nope.md")).isFalse();
    }

    @Test
    void delete_existingDoc_trashesAndReturnsTrue() {
        DocumentDocument doc = doc("dead.md", "");
        doc.setId("doc-123");
        when(documentService.findByPath("acme", "proj", "dead.md"))
                .thenReturn(Optional.of(doc));

        boolean deleted = api.documents.delete("dead.md");

        assertThat(deleted).isTrue();
        verify(documentService).trash(eq("doc-123"), any(WriteActor.class));
    }

    @Test
    void delete_missingDoc_returnsFalseWithoutTrashing() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(api.documents.delete("ghost.md")).isFalse();
        verify(documentService, never()).trash(any(), any(WriteActor.class));
    }

    @Test
    void list_returnsSummariesFromPagedListing() {
        DocumentDocument a = doc("notes/a.md", "");
        DocumentDocument b = doc("notes/b.md", "");
        Page<DocumentDocument> page = new PageImpl<>(
                List.of(a, b), PageRequest.of(0, 200), 2);
        when(documentService.listByProjectPaged("acme", "proj", 0, 200, "notes/"))
                .thenReturn(page);

        List<Map<String, Object>> out = api.documents.list("notes/");

        assertThat(out).hasSize(2);
        assertThat(out.get(0))
                .containsEntry("path", "notes/a.md")
                .containsKey("id")
                .containsKey("kind")
                .containsKey("size")
                .containsKey("tags")
                .containsKey("createdAt")
                .containsKey("version");
    }

    @Test
    void meta_existingDoc_returnsSummary() {
        DocumentDocument d = doc("config.yaml", "");
        d.setKind("config");
        when(documentService.findByPath("acme", "proj", "config.yaml"))
                .thenReturn(Optional.of(d));

        Map<String, Object> meta = api.documents.meta("config.yaml");

        assertThat(meta).containsEntry("path", "config.yaml")
                .containsEntry("kind", "config");
    }

    @Test
    void meta_missingDoc_throws() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.documents.meta("nope.md"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void emptyPath_isRejectedForRead() {
        assertThatThrownBy(() -> api.documents.read(""))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("path");
    }

    @Test
    void scriptWithoutProject_rejectsDocumentAccess() {
        VanceScriptApi noProject = new VanceScriptApi(
                contextTools("acme", null, null, null, "alice"),
                null, Set.of(), documentService);

        assertThatThrownBy(() -> noProject.documents.read("any.md"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("project");
    }

    @Test
    void apiWithoutDocumentService_hasNullDocumentsField() {
        VanceScriptApi noDocs = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), null);

        assertThat(noDocs.documents).isNull();
    }

    // ─── documentBasePath resolution (current path) ──────────────────────

    @Test
    void basePath_relativeWrite_resolvesUnderBasePath() {
        VanceScriptApi scoped = apiWithBasePath("apps/ws");
        scoped.documents.write("data/out.md", "x");
        verify(documentService).upsertText(
                eq("acme"), eq("proj"), eq("apps/ws/data/out.md"),
                eq(null), eq(null), eq("x"), eq("alice"), any());
    }

    @Test
    void basePath_relativeRead_resolvesUnderBasePath() {
        DocumentDocument doc = doc("apps/ws/data/in.json", "{}");
        when(documentService.findByPath("acme", "proj", "apps/ws/data/in.json"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("{}");

        assertThat(apiWithBasePath("apps/ws").documents.read("data/in.json")).isEqualTo("{}");
    }

    @Test
    void basePath_leadingSlash_isProjectRootAbsolute() {
        VanceScriptApi scoped = apiWithBasePath("apps/ws");
        scoped.documents.write("/shared/g.md", "x");
        verify(documentService).upsertText(
                eq("acme"), eq("proj"), eq("shared/g.md"),
                eq(null), eq(null), eq("x"), eq("alice"), any());
    }

    @Test
    void noBasePath_relativePathStaysProjectRootRelative() {
        // Default (no base) must be unchanged behaviour for other consumers.
        api.documents.write("data/out.md", "x");
        verify(documentService).upsertText(
                eq("acme"), eq("proj"), eq("data/out.md"),
                eq(null), eq(null), eq("x"), eq("alice"), any());
    }

    private VanceScriptApi apiWithBasePath(String basePath) {
        return new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), documentService, null, null, null, null, null, basePath);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ContextToolsApi contextTools(
            String tenant, String project, String session, String process, String user) {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(
                new ToolInvocationContext(tenant, project, session, process, user));
        return tools;
    }

    private static DocumentDocument doc(String path, String content) {
        DocumentDocument d = new DocumentDocument();
        d.setPath(path);
        d.setName(path.substring(path.lastIndexOf('/') + 1));
        d.setCreatedAt(Instant.now());
        d.setVersion(1L);
        return d;
    }
}
