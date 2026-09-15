package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.CreateContext;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The create path's seeding rule: an empty folder gets a running starter
 * design (the Bistromath Hello-World rule — the scaffold's acceptance is
 * something visibly running), a folder that already has designs gets none.
 */
class DesignerApplicationTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "web";

    private DocumentService documentService;
    private SecurityContextFactory contextFactory;
    private DesignerFolderReader folderReader;
    private DesignerApplication app;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        contextFactory = mock(SecurityContextFactory.class);
        folderReader = mock(DesignerFolderReader.class);
        var linkBuilder = mock(DocumentLinkBuilder.class);
        app = new DesignerApplication(documentService, contextFactory, folderReader, linkBuilder);

        when(linkBuilder.linkFor(any(), anyString())).thenReturn("[link](vance:...)");
        when(contextFactory.writeActor(anyString(), any(), anyString())).thenReturn(null);
        // Manifest does not exist; every create returns a row carrying its path.
        when(documentService.findByPath(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(documentService.create(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any(InputStream.class),
                        any(),
                        any()))
                .thenAnswer(inv -> doc(inv.getArgument(2)));
    }

    private static DocumentDocument doc(String path) {
        DocumentDocument d = new DocumentDocument();
        d.setPath(path);
        return d;
    }

    private CreateContext createContext() {
        return new CreateContext(TENANT, PROJECT, "designs", "alice", null, false, Map.of("title", "Website Redesign"));
    }

    @Test
    void create_seedsStarterDesignWhenFolderEmpty() {
        when(folderReader.scan(TENANT, PROJECT, "designs")).thenReturn(new DesignerFolderReader.Scan(List.of()));

        app.create(createContext());

        // Manifest + the three seed files (entry, stylesheet, metadata) + the
        // refresh-written catalogue — in that order.
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(documentService, org.mockito.Mockito.times(5))
                .create(
                        anyString(),
                        anyString(),
                        paths.capture(),
                        anyString(),
                        any(),
                        anyString(),
                        any(InputStream.class),
                        any(),
                        any());
        assertThat(paths.getAllValues())
                .containsExactly(
                        "designs/_app.yaml",
                        "designs/hello/index.html",
                        "designs/hello/style.css",
                        "designs/hello/design.yaml",
                        "designs/_index.md");
    }

    @Test
    void create_doesNotSeedWhenDesignsExist() {
        DesignerFolderReader.DesignScan existing =
                new DesignerFolderReader.DesignScan("landing", null, null, List.of("index.html"), 1, Map.of());
        when(folderReader.scan(TENANT, PROJECT, "designs"))
                .thenReturn(new DesignerFolderReader.Scan(List.of(existing)));

        var result = app.create(createContext());

        verify(documentService, never())
                .create(
                        anyString(),
                        anyString(),
                        eq("designs/hello/index.html"),
                        anyString(),
                        any(),
                        anyString(),
                        any(InputStream.class),
                        any(),
                        any());
        assertThat(result.nextStep()).contains("Create a design");
        assertThat(result.nextStep()).doesNotContain("starter design");
    }

    // ── promptInject: the open design rides with the turn ──────────

    @Test
    void promptInject_namesTheOpenDesignWithItsFiles() {
        DesignerFolderReader.DesignScan landing = new DesignerFolderReader.DesignScan(
                "landing", "Landing Page", "A page", List.of("index.html", "style.css"), 2, Map.of());
        when(folderReader.scan(TENANT, PROJECT, "designs")).thenReturn(new DesignerFolderReader.Scan(List.of(landing)));

        var ctx =
                new VanceApplication.PromptInjectContext(TENANT, PROJECT, "designs", "session-1", "proc-1", "landing");
        String inject = app.promptInject(ctx);

        assertThat(inject).contains("currently open in the preview is `landing`");
        assertThat(inject).contains("Landing Page");
        assertThat(inject).contains("`index.html`, `style.css`");
        assertThat(inject).contains("designs/landing/");
    }

    @Test
    void promptInject_staleOrMissingOpenDesignFallsAwaySilently() {
        DesignerFolderReader.DesignScan landing =
                new DesignerFolderReader.DesignScan("landing", null, null, List.of("index.html"), 1, Map.of());
        when(folderReader.scan(TENANT, PROJECT, "designs")).thenReturn(new DesignerFolderReader.Scan(List.of(landing)));

        // Deleted in another tab: the name no longer resolves.
        String stale = app.promptInject(
                new VanceApplication.PromptInjectContext(TENANT, PROJECT, "designs", "session-1", "proc-1", "ghost"));
        assertThat(stale).doesNotContain("currently open");

        // Nothing open at all.
        String none = app.promptInject(
                new VanceApplication.PromptInjectContext(TENANT, PROJECT, "designs", "session-1", "proc-1", null));
        assertThat(none).doesNotContain("currently open");
    }

    // ── Design operations ────────────────────────────────────────────

    @Test
    void createDesign_writesEntryFileAndMeta() {
        app.createDesign(TENANT, PROJECT, "designs", "landing", "Landing", "A page", "alice");

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(documentService, org.mockito.Mockito.times(2))
                .create(
                        anyString(),
                        anyString(),
                        paths.capture(),
                        anyString(),
                        any(),
                        anyString(),
                        any(InputStream.class),
                        any(),
                        any());
        assertThat(paths.getAllValues()).containsExactly("designs/landing/index.html", "designs/landing/design.yaml");
    }

    @Test
    void createDesign_withoutMetaWritesOnlyTheEntryFile() {
        app.createDesign(TENANT, PROJECT, "designs", "sketch", null, "  ", "alice");

        verify(documentService, org.mockito.Mockito.times(1))
                .create(
                        anyString(),
                        anyString(),
                        eq("designs/sketch/index.html"),
                        anyString(),
                        any(),
                        anyString(),
                        any(InputStream.class),
                        any(),
                        any());
    }

    @Test
    void createDesign_rejectsTraversalNames() {
        assertThatThrownBy(() -> app.createDesign(TENANT, PROJECT, "designs", "../evil", null, null, "alice"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("invalid design name");
        assertThatThrownBy(() -> app.createDesign(TENANT, PROJECT, "designs", "_reserved", null, null, "alice"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("invalid design name");
    }

    @Test
    void createDesign_rejectsExistingDesign() {
        when(documentService.findByPath(TENANT, PROJECT, "designs/landing/index.html"))
                .thenReturn(Optional.of(doc("designs/landing/index.html")));

        assertThatThrownBy(() -> app.createDesign(TENANT, PROJECT, "designs", "landing", null, null, "alice"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteDesign_trashesEveryFileOfTheDesign() {
        DocumentDocument entry = doc("designs/landing/index.html");
        entry.setId("id-1");
        DocumentDocument style = doc("designs/landing/style.css");
        style.setId("id-2");
        when(documentService.listUnderFolder(TENANT, PROJECT, "designs/landing/"))
                .thenReturn(List.of(entry, style));

        app.deleteDesign(TENANT, PROJECT, "designs", "landing", "alice");

        verify(documentService).trash(eq("id-1"), any());
        verify(documentService).trash(eq("id-2"), any());
        verify(documentService, never()).trash(eq("id-other"), any());
    }

    @Test
    void deleteDesign_rejectsUnknownDesign() {
        assertThatThrownBy(() -> app.deleteDesign(TENANT, PROJECT, "designs", "ghost", "alice"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no such design");
    }

    @Test
    void reorder_persistCleanedOrderIntoTheManifest() {
        DocumentDocument manifest = doc("designs/_app.yaml");
        manifest.setId("manifest-1");
        manifest.setTitle("Designer");
        manifest.setMimeType("application/yaml");
        when(documentService.findByPath(TENANT, PROJECT, "designs/_app.yaml")).thenReturn(Optional.of(manifest));
        when(documentService.readContent(manifest))
                .thenReturn("$meta:\n  kind: application\n  app: designer\ntitle: Designer\n");
        DesignerFolderReader.DesignScan alpha =
                new DesignerFolderReader.DesignScan("alpha", null, null, List.of("index.html"), 1, Map.of());
        DesignerFolderReader.DesignScan bravo =
                new DesignerFolderReader.DesignScan("bravo", null, null, List.of("index.html"), 1, Map.of());
        when(folderReader.scan(TENANT, PROJECT, "designs"))
                .thenReturn(new DesignerFolderReader.Scan(List.of(alpha, bravo)));

        app.reorder(TENANT, PROJECT, "designs", List.of("bravo", "ghost", "alpha"), "alice");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .update(
                        eq("manifest-1"),
                        anyString(),
                        any(),
                        body.capture(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        anyString(),
                        any(),
                        any());
        String yaml = body.getValue();
        // Stale names must not pin ghost positions; the rest keeps its order.
        assertThat(yaml).contains("order:");
        assertThat(yaml.indexOf("bravo")).isLessThan(yaml.indexOf("alpha"));
        assertThat(yaml).doesNotContain("ghost");
    }

    @Test
    void create_nextStepMentionsTheStarterDesignWhenSeeded() {
        when(folderReader.scan(TENANT, PROJECT, "designs")).thenReturn(new DesignerFolderReader.Scan(List.of()));

        var result = app.create(createContext());

        assertThat(result.nextStep()).contains("starter design 'hello'");
    }
}
