package de.mhus.vance.brain.tools.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Discovering and browsing mounts, and saying when a listing is stale. */
class MountListToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private KindToolSupport support;
    private DocumentService documentService;
    private JaglanShellService shellService;
    private MountListTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        support = mock(KindToolSupport.class);
        documentService = mock(DocumentService.class);
        shellService = mock(JaglanShellService.class);
        EddieContext eddieContext = mock(EddieContext.class);

        when(support.documentService()).thenReturn(documentService);
        when(support.eddieContext()).thenReturn(eddieContext);
        when(eddieContext.resolveProject(any(), any(), anyBoolean()))
                .thenReturn(ProjectDocument.builder().name(PROJECT).build());

        ObjectProvider<JaglanShellService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(shellService);
        tool = new MountListTool(support, provider);
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, null, "proc-1", "alice");
    }

    private static DocumentDocument entry(String path, boolean folder) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT)
                .path(path).name(path.substring(path.lastIndexOf('/') + 1))
                .size(7)
                .build();
        doc.setMountDirectory(folder);
        return doc;
    }

    @Test
    void withoutPath_listsTheConfiguredMounts() {
        when(documentService.listMounts(TENANT, PROJECT)).thenReturn(List.of(
                new MountedSource("library", "Book Library", "ode", MountAccess.RO, 42L,
                        null, Duration.ofMinutes(5), true)));

        Map<String, Object> out = tool.invoke(Map.of(), ctx());

        assertThat(out.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("mounts");
        assertThat(rows.get(0)).containsEntry("mount", "library")
                .containsEntry("path", "_ext/library")
                .containsEntry("access", "RO")
                .containsEntry("itemCount", 42L);
    }

    @Test
    void pathOutsideTheNamespace_isRefused() {
        Map<String, Object> out = tool.invoke(Map.of("path", "documents/notes"), ctx());

        assertThat(out.get("error").toString()).contains("_ext/");
        verify(documentService, never())
                .listMountedFolder(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void namespaceRootWithoutAMount_isARefusalNotAnException() {
        // '_ext/' is inside the namespace but names no mount, and the document
        // layer answers that with an IllegalArgumentException — which used to
        // escape the tool as a raw failure instead of a usable message.
        Map<String, Object> out = tool.invoke(Map.of("path", "_ext/"), ctx());

        assertThat(out.get("error").toString()).contains("must name a mount");
        verify(documentService, never())
                .listMountedFolder(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void folderListing_reportsFilesAndFolders() {
        when(documentService.listMountedFolder(TENANT, PROJECT, "_ext/library", false))
                .thenReturn(List.of(
                        entry("_ext/library/books", true),
                        entry("_ext/library/readme.md", false)));

        Map<String, Object> out = tool.invoke(Map.of("path", "_ext/library"), ctx());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("entries");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("folder", true).doesNotContainKey("size");
        assertThat(rows.get(1)).containsEntry("folder", false).containsEntry("size", 7L);
        assertThat(out).doesNotContainKey("status");
    }

    @Test
    void folderListing_afterAFailedRefresh_saysTheEntriesMayBeStale() {
        // Serving the old rows is deliberate, but nothing else says they are
        // old: the per-mount statusText comes from the capabilities cache and
        // is silent about a source that describes itself and cannot list this
        // one folder.
        Instant failedAt = Instant.parse("2026-08-22T10:15:30Z");
        when(documentService.listMountedFolder(TENANT, PROJECT, "_ext/library/books", false))
                .thenReturn(List.of(entry("_ext/library/books/dune.pdf", false)));
        when(shellService.folderFailure(TENANT, PROJECT, "library", "books"))
                .thenReturn(new JaglanShellService.FolderFailure(failedAt, "connect timeout"));

        Map<String, Object> out = tool.invoke(Map.of("path", "_ext/library/books"), ctx());

        assertThat(out.get("status").toString())
                .contains("connect timeout")
                .contains("out of date");
        assertThat(out).containsEntry("staleSince", failedAt.toString());
    }

    @Test
    void refreshFlag_isPassedThrough() {
        when(documentService.listMountedFolder(eq(TENANT), eq(PROJECT), eq("_ext/library"),
                anyBoolean())).thenReturn(List.of());

        tool.invoke(Map.of("path", "_ext/library", "refresh", true), ctx());

        verify(documentService).listMountedFolder(TENANT, PROJECT, "_ext/library", true);
    }
}
