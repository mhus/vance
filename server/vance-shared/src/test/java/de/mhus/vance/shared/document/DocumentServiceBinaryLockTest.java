package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Regression for the binary-write soft-lock gate (code-review F6): the
 * binary content paths must reject a write whose {@link
 * DocumentService.WriterIdentity} maps to a role in the document's
 * {@code lockedFor} set, exactly like the text paths do.
 */
class DocumentServiceBinaryLockTest {

    private DocumentRepository repository;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());
    }

    private DocumentDocument lockedDoc(String id, WriterRole... roles) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme")
                .projectId("proj")
                .path("images/pic.png")
                .mimeType("image/png")
                .build();
        doc.setId(id);
        doc.setLockedFor(EnumSet.copyOf(java.util.List.of(roles)));
        return doc;
    }

    @Test
    void replaceBinaryContent_toolWrite_blockedByAiLock() {
        when(repository.findById("d1")).thenReturn(Optional.of(lockedDoc("d1", WriterRole.AI)));

        // Default overload → TOOL_IDENTITY (AI role): must be blocked.
        assertThatThrownBy(() -> service.replaceBinaryContent(
                "d1", "image/png", new byte[] {1, 2, 3}, "someUser"))
                .isInstanceOf(DocumentService.DocumentLockedException.class);
    }

    @Test
    void replaceBinaryContent_officeUserWrite_blockedByUserLock() {
        when(repository.findById("d2")).thenReturn(Optional.of(lockedDoc("d2", WriterRole.USER)));

        // Office save callback passes a USER identity (real editorId).
        assertThatThrownBy(() -> service.replaceBinaryContent(
                "d2", "image/png", new byte[] {1}, "bob",
                DocumentService.WriterIdentity.of("bob", "bob", null)))
                .isInstanceOf(DocumentService.DocumentLockedException.class);
    }

    @Test
    void createOrReplaceBinary_replaceBranch_blockedByAiLock() {
        DocumentDocument doc = lockedDoc("d3", WriterRole.AI);
        when(repository.findByTenantIdAndProjectIdAndPath(
                "acme", "proj", "images/pic.png"))
                .thenReturn(Optional.of(doc));
        when(repository.findById("d3")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.createOrReplaceBinary(
                "acme", "proj", "images/pic.png", new byte[] {9},
                "image/png", null, null, null, "creator"))
                .isInstanceOf(DocumentService.DocumentLockedException.class);
    }
}
