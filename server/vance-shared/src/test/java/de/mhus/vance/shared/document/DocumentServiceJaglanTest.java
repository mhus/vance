package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import de.mhus.vance.shared.document.jaglan.JaglanPort;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The Jaglan redirect in {@link DocumentService}: a path under {@code _ext/}
 * must reach the mount port instead of {@link StorageService}, and the
 * operations that cannot work on foreign content must refuse by name rather
 * than silently doing nothing.
 *
 * <p>See {@code planning/jaglan-mounted-docs.md} §4 and §11.
 */
class DocumentServiceJaglanTest {

    private DocumentRepository repository;
    private StorageService storageService;
    private MongoTemplate mongoTemplate;
    private JaglanPort port;
    private DocumentService service;

    private static final WriteActor ACTOR = WriteActor.SYSTEM;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());
        port = mock(JaglanPort.class);
        installPort(port);
    }

    @SuppressWarnings("unchecked")
    private void installPort(@org.jspecify.annotations.Nullable JaglanPort value) {
        ObjectProvider<JaglanPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        ReflectionTestUtils.setField(service, "jaglanPortProvider", provider);
    }

    private static DocumentDocument mountedDoc() {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme")
                .projectId("research")
                .path("_ext/library/books/dune.pdf")
                .name("dune.pdf")
                .mimeType("application/pdf")
                .build();
        doc.setId("ext_deadbeef");
        return doc;
    }

    private static DocumentDocument ordinaryDoc() {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme")
                .projectId("research")
                .path("documents/notes.md")
                .name("notes.md")
                .mimeType("text/markdown")
                .storageId("blob-1")
                .build();
        doc.setId("d1");
        return doc;
    }

    // ─── read ───────────────────────────────────────────────────────────

    @Test
    void loadContent_mountedPath_streamsFromThePortNotStorage() throws IOException {
        when(port.open("acme", "research", "library", "books/dune.pdf", null))
                .thenReturn(new ByteArrayInputStream("pdf-bytes".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = service.loadContent(mountedDoc())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("pdf-bytes");
        }
        verify(storageService, never()).load(anyString());
    }

    @Test
    void loadContent_mountedPath_doesNotFallThroughToTheEmptyStream() throws IOException {
        // A mounted document has no storageId, so the pre-existing
        // "storageId == null → empty stream" branch would have made the
        // content read as "" — silently, which is the worst outcome.
        when(port.open(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ByteArrayInputStream("real".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = service.loadContent(mountedDoc())) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }
    }

    @Test
    void loadContent_noPortInProcess_failsByName() {
        installPort(null);

        assertThatThrownBy(() -> service.loadContent(mountedDoc()))
                .isInstanceOf(JaglanUnavailableException.class)
                .hasMessageContaining("no mount support");
    }

    @Test
    void loadContent_ordinaryPath_stillUsesStorage() throws IOException {
        when(storageService.load("blob-1"))
                .thenReturn(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = service.loadContent(ordinaryDoc())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("body");
        }
        verify(port, never()).open(anyString(), anyString(), anyString(), anyString());
    }

    // ─── write ──────────────────────────────────────────────────────────

    @Test
    void streamingStoreContent_mountedPath_writesThroughPortAndKeepsNoStorageId()
            throws IOException {
        when(port.write(eq("acme"), eq("research"), eq("library"), eq("books/dune.pdf"), any()))
                .thenReturn(new MountedStat(
                        "books/dune.pdf", false, 9, "application/pdf", "e1", null, MountAccess.RW));

        DocumentService.ContentWriteResult result;
        try (InputStream in = new ByteArrayInputStream("pdf-bytes".getBytes(StandardCharsets.UTF_8))) {
            result = service.streamingStoreContent(
                    "acme", "research", "_ext/library/books/dune.pdf", in);
        }

        assertThat(result.storageId()).isNull();
        assertThat(result.compressed()).isFalse();
        assertThat(result.originalSize()).isEqualTo(9);
        verify(storageService, never()).store(anyString(), anyString(), any());
    }

    @Test
    void streamingStoreContent_mountedPath_fallsBackToCountedBytesWhenSourceReportsNoSize()
            throws IOException {
        // A source that answers 0 has told us nothing; persisting 0 for a
        // non-empty document would be a lie we could have avoided.
        when(port.write(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    InputStream body = invocation.getArgument(4);
                    body.readAllBytes();
                    return new MountedStat(
                            "books/dune.pdf", false, 0, null, null, null, MountAccess.RW);
                });

        DocumentService.ContentWriteResult result;
        try (InputStream in = new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))) {
            result = service.streamingStoreContent(
                    "acme", "research", "_ext/library/books/dune.pdf", in);
        }

        assertThat(result.originalSize()).isEqualTo(5);
    }

    @Test
    void streamingStoreContent_mountedPath_noPort_failsByName() {
        installPort(null);

        assertThatThrownBy(() -> service.streamingStoreContent(
                "acme", "research", "_ext/library/x.pdf",
                new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(JaglanUnavailableException.class);
    }

    @Test
    void streamingStoreContent_ordinaryPath_stillHitsStorage() throws IOException {
        when(storageService.store(anyString(), anyString(), any()))
                .thenReturn(new StorageService.StorageInfo(
                        "blob-new", 4L, new java.util.Date(), "acme", "documents/notes.md"));

        try (InputStream in = new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))) {
            DocumentService.ContentWriteResult result =
                    service.streamingStoreContent("acme", "research", "documents/notes.md", in);
            assertThat(result.storageId()).isEqualTo("blob-new");
        }
        verify(port, never()).write(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ─── delete and trash ───────────────────────────────────────────────

    @Test
    void delete_mountedPath_deletesAtTheSourceAndTouchesNoBlob() {
        DocumentDocument doc = mountedDoc();
        when(repository.findById("ext_deadbeef")).thenReturn(Optional.of(doc));

        service.delete("ext_deadbeef", ACTOR);

        verify(port).delete("acme", "research", "library", "books/dune.pdf");
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void delete_mountedPath_sourceRefusal_propagates() {
        DocumentDocument doc = mountedDoc();
        when(repository.findById("ext_deadbeef")).thenReturn(Optional.of(doc));
        org.mockito.Mockito.doThrow(new JaglanAccessException("library", "read-only"))
                .when(port).delete(anyString(), anyString(), anyString(), anyString());

        // Dropping only the Mongo shell would make the document reappear on
        // the next stat — so a refusal has to stop the delete, not be logged.
        assertThatThrownBy(() -> service.delete("ext_deadbeef", ACTOR))
                .isInstanceOf(JaglanAccessException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void trash_mountedPath_isRefusedByName() {
        DocumentDocument doc = mountedDoc();
        when(repository.findById("ext_deadbeef")).thenReturn(Optional.of(doc));

        // Trash would move the path to _vance/trash/…, outside the namespace
        // the address and the derived id are built from.
        assertThatThrownBy(() -> service.trash("ext_deadbeef", ACTOR))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("cannot be trashed");
        verify(repository, never()).save(any());
    }

    // ─── summary and RAG exclusion ──────────────────────────────────────

    @Test
    void isRagEligible_mountedPath_isFalseEvenWithAnExplicitOverride() {
        DocumentDocument doc = mountedDoc();
        doc.setMimeType("text/markdown");
        doc.setRagEnabled(Boolean.TRUE);

        // Checked ahead of the override on purpose: indexing a foreign
        // library into our own vector store must not be reachable by flag.
        assertThat(service.isRagEligible(doc)).isFalse();
    }

    @Test
    void claimForSummary_queryExcludesTheMountNamespace() {
        service.claimForSummary("acme", "research", "pod-1", 1, java.time.Duration.ofMinutes(5));

        assertThat(capturedClaimQuery()).contains("_ext/");
    }

    @Test
    void claimForRagIndex_queryExcludesTheMountNamespace() {
        service.claimForRagIndex("acme", "research", "pod-1", 1, java.time.Duration.ofMinutes(5));

        assertThat(capturedClaimQuery()).contains("_ext/");
    }

    /** The claim query handed to {@code findAndModify}, rendered for matching. */
    private String capturedClaimQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(
                captor.capture(), any(), any(), eq(DocumentDocument.class));
        return captor.getValue().getQueryObject().toJson();
    }

    // ─── namespace predicate ────────────────────────────────────────────

    @Test
    void isMounted_agreesWithTheOtherPathPredicates() {
        assertThat(DocumentService.isMounted("_ext/library/x.pdf")).isTrue();
        assertThat(DocumentService.isMounted("documents/notes.md")).isFalse();
        assertThat(DocumentService.isMounted("_vance/trash/x")).isFalse();
        assertThat(DocumentService.isMounted(null)).isFalse();
    }
}
