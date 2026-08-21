package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.document.jaglan.JaglanShellService.MountFolderView;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Synthetic {@code _ext} injection into the folder surfaces.
 *
 * <p>The point being defended: shell rows appear only after a mount folder has
 * been listed, but nobody can list a folder that is not shown. Without the
 * injection the namespace has no entrance at all.
 *
 * <p>See {@code planning/jaglan-mounted-docs.md} §6a.
 */
class DocumentServiceMountFoldersTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private MongoTemplate mongoTemplate;
    private JaglanShellService shellService;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        DocumentRepository repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());
        shellService = mock(JaglanShellService.class);
        ReflectionTestUtils.setField(service, "shellService", shellService);
        // No ordinary documents unless a test says otherwise.
        when(mongoTemplate.findDistinct(any(Query.class), eq("path"),
                eq(DocumentDocument.class), eq(String.class))).thenReturn(List.of());
    }

    private void mounts(MountFolderView... views) {
        when(shellService.mountFolders(TENANT, PROJECT)).thenReturn(List.of(views));
    }

    private static MountFolderView mount(String name, Integer docs, Integer subs) {
        return new MountFolderView(name, docs, subs);
    }

    // ─── extractFolders ─────────────────────────────────────────────────

    @Test
    void extractFolders_noMountConfigured_showsNoExtFolder() {
        mounts();

        // Mounts are project-scoped and most projects have none; an empty
        // system folder in every project is noise.
        assertThat(service.extractFolders(TENANT, PROJECT, null)).isEmpty();
    }

    @Test
    void extractFolders_withMounts_injectsExactlyTwoLevels() {
        mounts(mount("library", 12, 2), mount("archive", null, null));

        assertThat(service.extractFolders(TENANT, PROJECT, null))
                .extracting(FolderInfo::path)
                .containsExactly("_ext", "_ext/archive", "_ext/library");
    }

    @Test
    void extractFolders_mountEntriesCarryTheNamespaceAsParent() {
        mounts(mount("library", 12, 2));

        List<FolderInfo> folders = service.extractFolders(TENANT, PROJECT, null);

        assertThat(folders).filteredOn(f -> f.path().equals("_ext/library"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.name()).isEqualTo("library");
                    assertThat(f.parentPath()).isEqualTo("_ext");
                    assertThat(f.documentCount()).isEqualTo(12);
                    assertThat(f.subfolderCount()).isEqualTo(2);
                });
    }

    @Test
    void extractFolders_namespaceRootCountsMountsButNotDocuments() {
        mounts(mount("library", 12, 2), mount("archive", 3, 0));

        assertThat(service.extractFolders(TENANT, PROJECT, null))
                .filteredOn(f -> f.path().equals("_ext"))
                .singleElement()
                .satisfies(f -> {
                    // Subfolder count is exactly the number of mounts and
                    // always true.
                    assertThat(f.subfolderCount()).isEqualTo(2);
                    // The document count aggregates sources that may not know
                    // their own size, so it stays unknown rather than becoming
                    // a sum with a hole in it.
                    assertThat(f.documentCount()).isNull();
                });
    }

    @Test
    void extractFolders_unknownCountsStayNullRatherThanZero() {
        mounts(mount("library", null, null));

        assertThat(service.extractFolders(TENANT, PROJECT, null))
                .filteredOn(f -> f.path().equals("_ext/library"))
                .singleElement()
                .satisfies(f -> {
                    // Zero would read as "empty folder" in a file tree.
                    assertThat(f.documentCount()).isNull();
                    assertThat(f.subfolderCount()).isNull();
                });
    }

    @Test
    void extractFolders_respectsTheParentFilter() {
        mounts(mount("library", 1, 0));

        assertThat(service.extractFolders(TENANT, PROJECT, "_ext"))
                .extracting(FolderInfo::path)
                .containsExactly("_ext", "_ext/library");
        assertThat(service.extractFolders(TENANT, PROJECT, "documents"))
                .isEmpty();
    }

    @Test
    void extractFolders_doesNotDuplicateAMountThatAlreadyHasRows() {
        mounts(mount("library", 1, 0));
        when(mongoTemplate.findDistinct(any(Query.class), eq("path"),
                eq(DocumentDocument.class), eq(String.class)))
                .thenReturn(List.of("_ext/library/books/dune.pdf"));

        assertThat(service.extractFolders(TENANT, PROJECT, null))
                .extracting(FolderInfo::path)
                .containsExactly("_ext", "_ext/library", "_ext/library/books");
    }

    @Test
    void extractFolders_withoutJaglan_behavesAsBefore() {
        ReflectionTestUtils.setField(service, "shellService", null);

        assertThat(service.extractFolders(TENANT, PROJECT, null)).isEmpty();
        verify(shellService, never()).mountFolders(anyString(), anyString());
    }

    // ─── listFolders ────────────────────────────────────────────────────

    @Test
    void listFolders_injectsTheSameSetAsExtractFolders() {
        mounts(mount("library", 1, 0));

        // A caller that only wants paths must not see a different set of
        // folders than one that wants the counts too.
        assertThat(service.listFolders(TENANT, PROJECT))
                .containsExactly("_ext", "_ext/library");
    }

    @Test
    void listFolders_noMounts_isUnchanged() {
        mounts();

        assertThat(service.listFolders(TENANT, PROJECT)).isEmpty();
    }
}
