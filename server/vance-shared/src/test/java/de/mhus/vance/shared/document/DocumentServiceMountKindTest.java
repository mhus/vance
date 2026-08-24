package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A mounted document learns its {@code kind} on the first read of its body.
 *
 * <p>It cannot learn it earlier: kind comes from the front matter, and a
 * mounted row is built from a {@code stat}, which deliberately fetches no
 * bytes — otherwise one folder listing would be a download per file. The first
 * read is the moment the content is there anyway.
 */
class DocumentServiceMountKindTest {

    private MongoTemplate mongoTemplate;
    private DocumentHeaderParser headerParser;
    private de.mhus.vance.shared.document.jaglan.JaglanPort port;
    private DocumentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DocumentRepository repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());

        port = mock(de.mhus.vance.shared.document.jaglan.JaglanPort.class);
        ObjectProvider<de.mhus.vance.shared.document.jaglan.JaglanPort> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        ReflectionTestUtils.setField(service, "jaglanPortProvider", provider);
        ReflectionTestUtils.setField(service, "shellService",
                new JaglanShellService(mongoTemplate, provider));
    }

    private DocumentDocument mountedMarkdown(String kind) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme").projectId("research")
                .path("_ext/library/notes/thoughts.md").name("thoughts.md")
                .mimeType("text/markdown").size(42)
                .build();
        doc.setId("ext_abc");
        doc.setKind(kind);
        return doc;
    }

    private void sourceServes(String body) {
        when(port.open(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void headerYields(String kind) {
        DocumentHeader header = new DocumentHeader();
        header.setKind(kind);
        header.setValues(new LinkedHashMap<>());
        when(headerParser.parse(anyString(), anyString())).thenReturn(Optional.of(header));
    }

    @Test
    void firstRead_learnsTheKindAndPersistsIt() {
        sourceServes("---\nkind: workpage\n---\nhello");
        headerYields("workpage");
        DocumentDocument doc = mountedMarkdown(null);

        String text = service.readContent(doc);

        assertThat(text).contains("hello");
        assertThat(doc.getKind()).isEqualTo("workpage");
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void secondRead_doesNotWriteAgain() {
        sourceServes("---\nkind: workpage\n---\nhello");
        headerYields("workpage");
        // Already known — the guard is what keeps this a one-off rather than a
        // Mongo write on every read of every mounted text file.
        DocumentDocument doc = mountedMarkdown("workpage");

        service.readContent(doc);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void bodyWithoutAKind_writesNothing() {
        sourceServes("just prose, no front matter");
        when(headerParser.parse(anyString(), anyString())).thenReturn(Optional.empty());

        service.readContent(mountedMarkdown(null));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void binaryMountedFile_isNeverParsed() {
        sourceServes("%PDF-1.7 binary junk");
        DocumentDocument pdf = DocumentDocument.builder()
                .tenantId("acme").projectId("research")
                .path("_ext/library/books/dune.pdf").name("dune.pdf")
                .mimeType("application/pdf").size(1234)
                .build();
        pdf.setId("ext_pdf");

        service.readContent(pdf);

        // Front matter in a PDF is not a thing; parsing one would be wasted
        // work on the largest files in a mount.
        verify(headerParser, never()).parse(anyString(), anyString());
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void ordinaryDocument_isUntouched() {
        DocumentDocument ordinary = DocumentDocument.builder()
                .tenantId("acme").projectId("research")
                .path("documents/notes.md").name("notes.md")
                .mimeType("text/markdown").storageId("blob-1").size(10)
                .build();
        ordinary.setId("d1");
        when(mongoTemplate.findById(anyString(), eq(DocumentDocument.class))).thenReturn(null);
        // Non-mounted rows get their kind from applyHeader on write; the
        // read path must not start second-guessing them.
        service.readContent(ordinary);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class));
    }

    @Test
    void failedWrite_doesNotBreakTheRead() {
        sourceServes("---\nkind: workpage\n---\nhello");
        headerYields("workpage");
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class)))
                .thenThrow(new IllegalStateException("mongo down"));

        // Best-effort by design: the worst case is that the next read retries.
        assertThat(service.readContent(mountedMarkdown(null))).contains("hello");
    }

    @Test
    void refreshMounts_dropsTheCachesThroughThePort() {
        service.listMounts("acme", "research", /* refresh */ true);

        verify(port).refresh("acme", "research");
    }

    @Test
    void listMountsWithoutRefresh_doesNotEvict() {
        service.listMounts("acme", "research");

        verify(port, never()).refresh(anyString(), anyString());
    }
}
