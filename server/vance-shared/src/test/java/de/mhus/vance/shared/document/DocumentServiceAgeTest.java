package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.shared.document.kind.AgeKindHandler;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Server-side administration of age-encrypted documents — no crypto, only
 * the marker plumbing: the kind carried by the mime type instead of the
 * body, the armor shape guard on every write path, and the summary / RAG
 * exclusions. See {@code planning/age-encryption.md} §4.
 */
class DocumentServiceAgeTest {

    private static final String ARMOR = """
            -----BEGIN AGE ENCRYPTED FILE-----
            YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSB0QXVkQmNwZ3Z6YnNRZDJP
            WlFId3hyeFNmRS9SdUVUTkFhY1FXSno5VUFB
            -----END AGE ENCRYPTED FILE-----
            """;

    private DocumentRepository repository;
    private InMemoryStorage storageService;
    private MongoTemplate mongoTemplate;
    private DocumentHeaderParser headerParser;
    private DocumentService service;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(DocumentRepository.class);
        storageService = new InMemoryStorage();
        mongoTemplate = mock(MongoTemplate.class);
        headerParser = mock(DocumentHeaderParser.class);
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                mock(ResourcePatternResolver.class), headerParser,
                mock(DocumentArchiveService.class),
                mock(de.mhus.vance.shared.settings.SettingService.class),
                DocTestSupport.permissionProvider());
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
        ReflectionTestUtils.setField(service, "archiveEnabledDefault", false);
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    void create_ageMime_armoredBody_typesDocAsAge() {
        when(repository.existsByTenantIdAndProjectIdAndPath(any(), any(), any()))
                .thenReturn(false);
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DocumentDocument saved = service.create(
                "t1", "p1", "documents/secret.md.age", null, null,
                AgeDocumentKind.MIME_TYPE,
                new ByteArrayInputStream(ARMOR.getBytes(StandardCharsets.UTF_8)),
                "alice", WriteActor.SYSTEM);

        // The body cannot declare a kind — the mime type is the carrier,
        // re-asserted on every save (applyHeader's age branch).
        assertThat(saved.getKind()).isEqualTo("age");
        assertThat(saved.getHeaders()).isEmpty();
        // Ciphertext is neither summarised nor embedded — the defaults
        // must reflect that without anyone flipping flags.
        assertThat(saved.isAutoSummary()).isFalse();
        assertThat(saved.isRagDirty()).isFalse();
    }

    @Test
    void create_ageMime_plainTextBody_refused() {
        when(repository.existsByTenantIdAndProjectIdAndPath(any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(
                "t1", "p1", "documents/secret.md.age", null, null,
                AgeDocumentKind.MIME_TYPE,
                new ByteArrayInputStream("# not encrypted".getBytes(StandardCharsets.UTF_8)),
                "alice", WriteActor.SYSTEM))
                .isInstanceOf(DocumentService.AgeContentException.class)
                .hasMessageContaining("armored ciphertext");
    }

    @Test
    void createText_ageExtension_derivesAgeMimeAndRequiresArmor() {
        when(repository.existsByTenantIdAndProjectIdAndPath(any(), any(), any()))
                .thenReturn(false);
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DocumentDocument saved = service.createText(
                "t1", "p1", "documents/secret.md.age", null, null,
                ARMOR, "alice", WriteActor.SYSTEM);

        assertThat(saved.getMimeType()).isEqualTo(AgeDocumentKind.MIME_TYPE);
        assertThat(saved.getKind()).isEqualTo("age");
    }

    @Test
    void mimeFromPath_ageExtension_mapsToAgeMime() {
        assertThat(DocumentService.mimeFromPath("x/bericht.md.age"))
                .isEqualTo(AgeDocumentKind.MIME_TYPE);
        // Double extension keeps its parts: only the trailing segment
        // decides the stored mime.
        assertThat(DocumentService.mimeFromPath("x/bericht.age.md"))
                .isEqualTo("text/markdown");
    }

    // ───────────────────────── write guards ─────────────────────────

    @Test
    void replaceContent_plainTextOnAgeDoc_refused() {
        DocumentDocument doc = ageDoc();
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.replaceContent("doc-1",
                new ByteArrayInputStream("decrypted".getBytes(StandardCharsets.UTF_8)),
                null, DocumentService.TOOL_IDENTITY, WriteActor.SYSTEM))
                .isInstanceOf(DocumentService.AgeContentException.class)
                .hasMessageContaining("secret.md.age");
    }

    @Test
    void replaceContent_armoredBodyOnAgeDoc_updatesAndKeepsKind() {
        DocumentDocument doc = ageDoc();
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String fresh = ARMOR.replace("WlFId3hy", "WkFJZDNye");
        DocumentDocument saved = service.replaceContent("doc-1",
                new ByteArrayInputStream(fresh.getBytes(StandardCharsets.UTF_8)),
                null, DocumentService.TOOL_IDENTITY, WriteActor.SYSTEM);

        assertThat(saved.getKind()).isEqualTo("age");
    }

    @Test
    void update_plainTextOnAgeDoc_refused() {
        DocumentDocument doc = ageDoc();
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.update("doc-1", null, null,
                "now plaintext", null, null, null, null, null,
                DocumentService.TOOL_IDENTITY, WriteActor.SYSTEM))
                .isInstanceOf(DocumentService.AgeContentException.class);
    }

    @Test
    void replaceBinaryContent_binaryOnAgeDoc_refused() {
        DocumentDocument doc = ageDoc();
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.replaceBinaryContent("doc-1", null,
                new byte[] {(byte) 0x89, 'P', 'N', 'G'},
                null, DocumentService.TOOL_IDENTITY, WriteActor.SYSTEM))
                .isInstanceOf(DocumentService.AgeContentException.class);
    }

    // ───────────────────────── summary / RAG exclusion ─────────────────────────

    @Test
    void isRagEligible_ageDoc_refusedEvenWithOverride() {
        DocumentDocument doc = new DocumentDocument();
        doc.setTenantId("t1");
        doc.setProjectId("p1");
        doc.setPath("documents/secret.md.age");
        doc.setKind("age");
        doc.setMimeType(AgeDocumentKind.MIME_TYPE);
        // An explicit opt-in must not reach the vector store either —
        // indexing ciphertext is noise, same reasoning as mounted docs.
        doc.setRagEnabled(Boolean.TRUE);

        assertThat(service.isRagEligible(doc)).isFalse();
    }

    @Test
    void claimForSummary_queryExcludesAgeKind() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), any(Class.class)))
                .thenReturn(null);

        service.claimForSummary("t1", "p1", "pod-1", 5, Duration.ofMinutes(5));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, atLeastOnce()).findAndModify(captor.capture(),
                any(Update.class), any(FindAndModifyOptions.class), any(Class.class));
        org.bson.Document queryObject = captor.getValue().getQueryObject();
        assertThat(queryObject.get("kind"))
                .isEqualTo(new org.bson.Document("$ne", "age"));
    }

    // ───────────────────────── markers & detection ─────────────────────────

    @Test
    void ageDocumentKind_markers() {
        assertThat(AgeDocumentKind.isAgeEncrypted("age", null)).isTrue();
        assertThat(AgeDocumentKind.isAgeEncrypted(null, AgeDocumentKind.MIME_TYPE)).isTrue();
        assertThat(AgeDocumentKind.isAgeEncrypted(null,
                AgeDocumentKind.MIME_TYPE + "; charset=utf-8")).isTrue();
        assertThat(AgeDocumentKind.isAgeEncrypted("workpage", "text/markdown")).isFalse();
        assertThat(AgeDocumentKind.isAgeEncrypted(null, null)).isFalse();

        assertThat(AgeDocumentKind.hasAgeExtension("a/b.md.age")).isTrue();
        assertThat(AgeDocumentKind.hasAgeExtension("a/agent")).isFalse();
        assertThat(AgeDocumentKind.hasAgeExtension(null)).isFalse();

        assertThat(AgeDocumentKind.looksArmored(ARMOR)).isTrue();
        assertThat(AgeDocumentKind.looksArmored("# no")).isFalse();
        assertThat(AgeDocumentKind.looksArmored((String) null)).isFalse();
        assertThat(AgeDocumentKind.looksArmored(ARMOR.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(AgeDocumentKind.looksArmored(new byte[] {1, 2, 3})).isFalse();
        assertThat(AgeDocumentKind.looksArmored((byte[]) null)).isFalse();
    }

    @Test
    void ageKindHandler_detectsArmorMarker() {
        AgeKindHandler handler = new AgeKindHandler();
        assertThat(handler.getName()).isEqualTo("age");
        assertThat(handler.detects(ARMOR)).isTrue();
        assertThat(handler.detects("plain text")).isFalse();
        // Armor buried below other content is NOT a claim — the marker is
        // only meaningful as the body's opening line.
        assertThat(handler.detects("intro\n" + ARMOR)).isFalse();
    }

    @Test
    void frontMatter_ageArmor_isNotHeader() {
        // Regression: the armor's leading dashes must never be read as a
        // front-matter fence — a misparse would project garbage headers
        // and a wrong kind onto every age document.
        assertThat(FrontMatter.parse(ARMOR).hasHeader()).isFalse();
        assertThat(FrontMatter.parse(ARMOR).body()).isEqualTo(ARMOR);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** A persisted age document with real bytes behind its storageId. */
    private DocumentDocument ageDoc() {
        when(repository.existsByTenantIdAndProjectIdAndPath(any(), any(), any()))
                .thenReturn(false);
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        DocumentDocument doc = service.create(
                "t1", "p1", "documents/secret.md.age", null, null,
                AgeDocumentKind.MIME_TYPE,
                new ByteArrayInputStream(ARMOR.getBytes(StandardCharsets.UTF_8)),
                "alice", WriteActor.SYSTEM);
        doc.setId("doc-1");
        return doc;
    }

    /** In-memory storage — same shape as DocumentServiceStreamingTest's. */
    private static class InMemoryStorage extends StorageService {
        private final Map<String, byte[]> blobs = new HashMap<>();

        @Override
        public StorageInfo store(String tenantId, String path, InputStream stream) {
            String id = UUID.randomUUID().toString();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            long size = 0;
            byte[] buf = new byte[8 * 1024];
            try {
                int n;
                while ((n = stream.read(buf)) > 0) {
                    sink.write(buf, 0, n);
                    size += n;
                }
            } catch (IOException e) {
                throw new IllegalStateException("test storage read failed", e);
            }
            blobs.put(id, sink.toByteArray());
            return new StorageInfo(id, size, new Date(), tenantId, path);
        }

        @Override
        public @Nullable InputStream load(String storageId) {
            byte[] data = blobs.get(storageId);
            return data == null ? null : new ByteArrayInputStream(data);
        }

        @Override
        public void delete(String storageId) {
            blobs.remove(storageId);
        }

        @Override
        public StorageInfo update(String storageId, InputStream stream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StorageInfo replace(String storageId, InputStream stream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable StorageInfo info(String storageId) {
            byte[] data = blobs.get(storageId);
            return data == null
                    ? null
                    : new StorageInfo(storageId, data.length, new Date(), null, null);
        }

        @Override
        public @Nullable String duplicate(String sourceStorageId, String targetTenantId) {
            byte[] data = blobs.get(sourceStorageId);
            if (data == null) return null;
            String id = UUID.randomUUID().toString();
            blobs.put(id, data.clone());
            return id;
        }

        @Override
        public void forEachFinalStorageIdOlderThan(
                java.time.Instant cutoff, int batchSize,
                java.util.function.Consumer<java.util.List<String>> batchHandler) {
            // Age test doesn't drive the orphan sweep — no-op is safe.
        }
    }
}
