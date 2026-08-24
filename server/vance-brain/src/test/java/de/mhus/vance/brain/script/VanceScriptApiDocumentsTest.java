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
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import de.mhus.vance.toolpack.ToolInvocationContext;
import org.mockito.ArgumentCaptor;
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
        // The two-argument form, because that is the one read() calls.
        when(documentService.readContent(doc, null)).thenReturn("Hello world");

        String text = api.documents.read("notes/hello.md");

        assertThat(text).isEqualTo("Hello world");
    }

    // ─── a query in the path must not be dropped ────────────────────────

    @Test
    void read_pathWithQuery_forwardsItRatherThanReadingThePlainDocument() {
        // DocumentRefResolver splits a ?query off every path it is given.
        // Taking ref.path() and ignoring the rest returns the plain document
        // while the script believes it asked for a view of it — a wrong answer
        // in the shape of a right one, and the exact failure this whole
        // feature exists to prevent.
        DocumentDocument doc = doc("_ext/demo/analysis.yaml", "");
        when(documentService.findByPath("acme", "proj", "_ext/demo/analysis.yaml"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc, "from=2026-02-01&to=2026-03-31"))
                .thenReturn("computed:");

        String text = api.documents.read("/_ext/demo/analysis.yaml?from=2026-02-01&to=2026-03-31");

        assertThat(text).isEqualTo("computed:");
        verify(documentService).readContent(doc, "from=2026-02-01&to=2026-03-31");
    }

    @Test
    void read_queryAgainstAStoredDocument_surfacesTheRefusal() {
        // A stored document has nothing to parameterise. DocumentService says
        // so; the script API must pass that on as a normal Error instead of
        // letting an IllegalArgumentException escape into the engine.
        DocumentDocument doc = doc("apps/thing/main.js", "");
        when(documentService.findByPath("acme", "proj", "apps/thing/main.js"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc, "from=2026-02-01"))
                .thenThrow(new IllegalArgumentException(
                        "document 'apps/thing/main.js' is stored, not mounted"));

        assertThatThrownBy(() -> api.documents.read("/apps/thing/main.js?from=2026-02-01"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("stored, not mounted");
    }

    @Test
    void write_pathWithQuery_isRefused() {
        // Writing "the view" is not a thing. Dropping the query would write to
        // a different document than the one named.
        assertThatThrownBy(() -> api.documents.write("notes/a.md?x=1", "body"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("only read()");
    }

    @Test
    void exists_pathWithQuery_isRefused() {
        assertThatThrownBy(() -> api.documents.exists("notes/a.md?x=1"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("only read()");
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
    void write_carriesUserReasonActor_notSystemBypass() {
        // Security regression (code-review-2 B1): a script-supplied path is a
        // user-driven write, so the actor must carry WriteReason.USER with the
        // run's real subject — NOT WriteActor.SYSTEM (which would fail-open past
        // the reserved-prefix ADMIN gate at the DocumentService chokepoint).
        api.documents.write("notes/new.md", "# New");

        ArgumentCaptor<WriteActor> actor = ArgumentCaptor.forClass(WriteActor.class);
        verify(documentService).upsertText(
                any(), any(), any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue().reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.getValue().subject()).isNotEqualTo(SecurityContext.SYSTEM);
    }

    @Test
    void delete_carriesUserReasonActor_notSystemBypass() {
        DocumentDocument doc = doc("dead.md", "x");
        doc.setId("doc-123");
        when(documentService.findByPath("acme", "proj", "dead.md"))
                .thenReturn(Optional.of(doc));

        api.documents.delete("dead.md");

        ArgumentCaptor<WriteActor> actor = ArgumentCaptor.forClass(WriteActor.class);
        verify(documentService).trash(eq("doc-123"), actor.capture());
        assertThat(actor.getValue().reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.getValue().subject()).isNotEqualTo(SecurityContext.SYSTEM);
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
        assertThatThrownBy(() -> api.documents.write("_vance/trash/sneaky.md", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("_vance/trash/");
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
        when(documentService.readContent(doc, null)).thenReturn("{}");

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

    // ─── DocumentRefResolver hardening (single-doc access) ───────────────

    @Test
    void crossProjectPath_isRejected() {
        assertThatThrownBy(() -> api.documents.read("//other/secret.md"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("cross-project");
        verify(documentService, never()).findByPath(any(), any(), any());
    }

    @Test
    void dotDotWithinBasePath_isCanonicalised() {
        apiWithBasePath("apps/ws").documents.write("../shared/g.md", "x");
        verify(documentService).upsertText(
                eq("acme"), eq("proj"), eq("apps/shared/g.md"),
                eq(null), eq(null), eq("x"), eq("alice"), any());
    }

    @Test
    void dotDotEscapingRoot_isRejected() {
        assertThatThrownBy(() -> api.documents.write("../../etc/passwd", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("bad path");
        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private VanceScriptApi apiWithBasePath(String basePath) {
        return new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), documentService, null, null, null, null, null, basePath, null);
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
