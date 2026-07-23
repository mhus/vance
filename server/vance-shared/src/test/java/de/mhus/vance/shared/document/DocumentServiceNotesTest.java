package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class DocumentServiceNotesTest {

    private DocumentRepository repository;
    private MongoTemplate mongoTemplate;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
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
    }

    // ── addNote ────────────────────────────────────────────────────────

    @Test
    void addNote_unknownDocument_throws() {
        when(repository.existsById("missing")).thenReturn(false);
        assertThatThrownBy(() -> service.addNote("missing", "hi", "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void addNote_persistsViaFindAndModify_andReturnsNoteWithGeneratedId() {
        when(repository.existsById("doc-1")).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenAnswer(inv -> {
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("doc-1");
                    return doc;
                });

        DocumentNote note = service.addNote("doc-1", "first thought", "alice", 4);

        assertThat(note.getId()).isNotBlank();
        assertThat(note.getText()).isEqualTo("first thought");
        assertThat(note.getUserId()).isEqualTo("alice");
        assertThat(note.getLine()).isEqualTo(4);
        assertThat(note.isDone()).isFalse();
        assertThat(note.getCreatedAt()).isNotNull();
        assertThat(note.getUpdatedAt()).isEqualTo(note.getCreatedAt());

        verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class));
    }

    @Test
    void addNote_atCap_throwsNotesLimitExceeded() {
        when(repository.existsById("doc-1")).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.addNote("doc-1", "boom", "alice", null))
                .isInstanceOf(DocumentService.NotesLimitExceededException.class)
                .hasMessageContaining("doc-1")
                .hasMessageContaining(String.valueOf(DocumentService.NOTES_MAX));
    }

    // ── updateNote ─────────────────────────────────────────────────────

    @Test
    void updateNote_patchesTextAndDone_andBumpsUpdatedAt() {
        DocumentNote stored = DocumentNote.builder()
                .id("n-1").text("new text").userId("alice")
                .createdAt(Instant.now()).updatedAt(Instant.now()).done(true)
                .build();
        DocumentDocument modified = new DocumentDocument();
        modified.setId("doc-1");
        Map<String, DocumentNote> notes = new LinkedHashMap<>();
        notes.put("n-1", stored);
        modified.setNotes(notes);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(modified);

        Optional<DocumentNote> result = service.updateNote("doc-1", "n-1", "new text", true, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("n-1");
        assertThat(result.get().getText()).isEqualTo("new text");
        assertThat(result.get().isDone()).isTrue();
    }

    @Test
    void updateNote_unknownNote_returnsEmpty() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(null);

        assertThat(service.updateNote("doc-1", "missing-note", "x", null, null)).isEmpty();
    }

    // ── deleteNote ─────────────────────────────────────────────────────

    @Test
    void deleteNote_existing_returnsTrue() {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class))).thenReturn(result);

        assertThat(service.deleteNote("doc-1", "n-1")).isTrue();
    }

    @Test
    void deleteNote_missing_returnsFalse() {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(DocumentDocument.class))).thenReturn(result);

        assertThat(service.deleteNote("doc-1", "missing-note")).isFalse();
    }

    @Test
    void deleteNote_unknownDocument_returnsFalse() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());
        assertThat(service.deleteNote("ghost", "n-1")).isFalse();
    }

    // ── listNotes ──────────────────────────────────────────────────────

    @Test
    void listNotes_unknownDocument_returnsEmpty() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThat(service.listNotes("missing")).isEmpty();
    }

    @Test
    void listNotes_preservesInsertionOrder() {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        Map<String, DocumentNote> notes = new LinkedHashMap<>();
        notes.put("n-1", DocumentNote.builder().id("n-1").text("first").userId("alice").build());
        notes.put("n-2", DocumentNote.builder().id("n-2").text("second").userId("alice").build());
        notes.put("n-3", DocumentNote.builder().id("n-3").text("third").userId("bob").build());
        doc.setNotes(notes);
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThat(service.listNotes("doc-1"))
                .extracting(DocumentNote::getId)
                .containsExactly("n-1", "n-2", "n-3");
    }
}
