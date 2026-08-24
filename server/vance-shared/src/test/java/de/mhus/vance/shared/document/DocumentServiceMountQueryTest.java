package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.document.jaglan.JaglanPort;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The read seam for a <b>parameterised</b> mount read.
 *
 * <p>The property under test is that a query reaches the source and never
 * reaches a path. Paths derive document ids and key the Mongo row; a {@code ?}
 * inside one would make {@code (tenant, project, path)} ambiguous and put a
 * query into every extension and mime decision in the tree.
 *
 * <p>Spec: {@code specification/public/jaglan-system.md} §4.4,
 * {@code planning/jaglan-query-views.md}.
 */
class DocumentServiceMountQueryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNTED_PATH = "_ext/hrafnagud/analysis.yaml";

    private JaglanPort port;
    private StorageService storageService;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        DocumentRepository repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        PermissionService permissionService = mock(PermissionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> psp = mock(ObjectProvider.class);
        when(psp.getObject()).thenReturn(permissionService);

        service = new DocumentService(
                repository, storageService, mock(MongoTemplate.class),
                mock(ResourcePatternResolver.class), mock(DocumentHeaderParser.class),
                mock(DocumentArchiveService.class), mock(SettingService.class), psp);

        port = mock(JaglanPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JaglanPort> jp = mock(ObjectProvider.class);
        when(jp.getIfAvailable()).thenReturn(port);
        ReflectionTestUtils.setField(service, "jaglanPortProvider", jp);
    }

    @Test
    void loadContent_mountedWithQuery_handsTheQueryToTheSourceAndKeepsThePathClean() {
        when(port.open(eq(TENANT), eq(PROJECT), eq("hrafnagud"), eq("analysis.yaml"),
                eq("from=2026-01&to=2026-06")))
                .thenReturn(stream("chart:"));

        InputStream in = service.loadContent(mounted(), "from=2026-01&to=2026-06");

        assertThat(read(in)).isEqualTo("chart:");
        // The path argument carries no '?': that is the whole invariant.
        verify(port).open(TENANT, PROJECT, "hrafnagud", "analysis.yaml",
                "from=2026-01&to=2026-06");
    }

    @Test
    void loadContent_mountedWithoutQuery_takesThePlainPath() {
        when(port.open(eq(TENANT), eq(PROJECT), eq("hrafnagud"), eq("analysis.yaml"), isNull()))
                .thenReturn(stream("plain"));

        assertThat(read(service.loadContent(mounted()))).isEqualTo("plain");
    }

    @Test
    void loadContent_mountedWithBlankQuery_reachesTheSourceAsNoQueryAtAll() {
        // A blank query is not a parameterised read, and it must arrive as
        // null rather than as "  ": a mount that serves no parameters would
        // otherwise refuse an ordinary download.
        when(port.open(eq(TENANT), eq(PROJECT), eq("hrafnagud"), eq("analysis.yaml"), isNull()))
                .thenReturn(stream("plain"));

        assertThat(read(service.loadContent(mounted(), "  "))).isEqualTo("plain");
        verify(port).open(TENANT, PROJECT, "hrafnagud", "analysis.yaml", null);
    }

    @Test
    void loadContent_storedDocumentWithQuery_isRefusedRatherThanIgnored() {
        // There is nothing to parameterise about bytes we hold. Ignoring the
        // query would return the plain document — a wrong answer in the shape
        // of a right one.
        DocumentDocument stored = DocumentDocument.builder()
                .id("doc-1").tenantId(TENANT).projectId(PROJECT)
                .path("documents/report.yaml").storageId("blob-1").build();

        assertThatThrownBy(() -> service.loadContent(stored, "from=2026-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents/report.yaml");
        verify(storageService, never()).load(any());
    }

    @Test
    void loadContent_storedDocumentWithoutQuery_isUntouchedByTheOverload() {
        DocumentDocument stored = DocumentDocument.builder()
                .id("doc-1").tenantId(TENANT).projectId(PROJECT)
                .path("documents/report.yaml").storageId("blob-1").build();
        when(storageService.load("blob-1")).thenReturn(stream("stored"));

        assertThat(read(service.loadContent(stored))).isEqualTo("stored");
    }

    private static DocumentDocument mounted() {
        return DocumentDocument.builder()
                .id(JaglanPaths.documentIdForPath(TENANT, PROJECT, MOUNTED_PATH))
                .tenantId(TENANT).projectId(PROJECT).path(MOUNTED_PATH)
                .name("analysis.yaml").build();
    }

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) {
        try (InputStream open = in) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
