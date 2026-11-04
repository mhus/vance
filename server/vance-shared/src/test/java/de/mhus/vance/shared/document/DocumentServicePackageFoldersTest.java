package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService.FolderPackage;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Which subfolders of a listing are packages — folders holding an application
 * manifest. The tree needs this <em>before</em> a folder is opened, so it has
 * to come with the folder listing itself, from one query per level.
 */
class DocumentServicePackageFoldersTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private MongoTemplate mongoTemplate;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        service = new DocumentService(
                mock(DocumentRepository.class),
                mock(StorageService.class),
                mongoTemplate,
                mock(ResourcePatternResolver.class),
                mock(DocumentHeaderParser.class),
                mock(DocumentArchiveService.class),
                mock(SettingService.class),
                DocTestSupport.permissionProvider());
    }

    private static DocumentDocument manifest(String path, String kind, String app) {
        DocumentDocument doc = new DocumentDocument();
        doc.setPath(path);
        doc.setKind(kind);
        Map<String, String> headers = new LinkedHashMap<>();
        if (app != null) headers.put("app", app);
        doc.setHeaders(headers);
        return doc;
    }

    private void manifests(DocumentDocument... docs) {
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class))).thenReturn(List.of(docs));
    }

    @Test
    void foldersWithAManifestAreReportedWithTheirAppType() {
        manifests(
                manifest("apps/kanban1/_app.yaml", "application", "kanban"),
                manifest("apps/wiki/_app.yaml", "application", "wiki"));

        List<FolderPackage> packages =
                service.packageFolders(TENANT, PROJECT, "apps/", List.of("kanban1", "notes", "wiki"));

        assertThat(packages)
                .containsExactly(
                        new FolderPackage("kanban1", "application", "kanban"),
                        new FolderPackage("wiki", "application", "wiki"));
    }

    @Test
    void aManifestWithoutAnAppTypeIsStillAPackage() {
        manifests(manifest("apps/plain/_app.yaml", "application", null));

        assertThat(service.packageFolders(TENANT, PROJECT, "apps/", List.of("plain")))
                .containsExactly(new FolderPackage("plain", "application", null));
    }

    @Test
    void aManifestOutsideTheListedFoldersIsIgnored() {
        // A search can narrow `folders` below what the manifest query matched.
        manifests(manifest("apps/kanban1/_app.yaml", "application", "kanban"));

        assertThat(service.packageFolders(TENANT, PROJECT, "apps/", List.of("wiki")))
                .isEmpty();
    }

    @Test
    void theRootLevelHasAnEmptyPrefix() {
        manifests(manifest("shop/_app.yaml", "application", "kanban"));

        assertThat(service.packageFolders(TENANT, PROJECT, "", List.of("shop")))
                .containsExactly(new FolderPackage("shop", "application", "kanban"));
    }

    @Test
    void noSubfoldersMeansNoQuery() {
        assertThat(service.packageFolders(TENANT, PROJECT, "apps/", List.of())).isEmpty();

        verify(mongoTemplate, never()).find(any(Query.class), eq(DocumentDocument.class));
    }
}
