package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the store reads and refuses: schedules are YAML a human may edit,
 * and every way a document can be wrong must end in "ignored", never in
 * "fires on every wakeup".
 */
class TrillianScheduleStoreParsingTest {

    private static final String HOME = "_user__trillian-void-1234";

    private final DocumentService documentService = mock(DocumentService.class);
    private final TrillianScheduleStore store = new TrillianScheduleStore(documentService);

    @Test
    void requireValidName_refusesASlash() {
        // A name with '/' files the document in a sub-folder the store cannot
        // write back to — the original stayed due forever.
        assertThatThrownBy(() -> TrillianScheduleStore.requireValidName("work/standup"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrillianScheduleStore.requireValidName("Standup"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TrillianScheduleStore.requireValidName(" morning-briefing_2 "))
                .isEqualTo("morning-briefing_2");
    }

    @Test
    void save_refusesAnInvalidName_beforeWriting() {
        TrillianScheduleStore.Schedule bad =
                new TrillianScheduleStore.Schedule("a/b", null, Instant.now(), null, "x", true, null);

        assertThatThrownBy(() -> store.save("acme", HOME, bad)).isInstanceOf(IllegalArgumentException.class);
        verify(documentService, never())
                .upsertText(anyString(), anyString(), anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void save_propagatesAFailedWrite() {
        // A tool must not report "scheduled" for an appointment never stored.
        when(documentService.upsertText(anyString(), anyString(), anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThatThrownBy(() -> store.save(
                        "acme",
                        HOME,
                        new TrillianScheduleStore.Schedule("x", null, Instant.now(), null, "p", true, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void list_readsAnUnquotedTimestamp() {
        // Hand-edited YAML: SnakeYAML loads an unquoted ISO timestamp as a
        // Date, whose toString is not ISO — the entry used to vanish.
        givenDocument(TrillianScheduleStore.pathFor("standup"), "due: 2026-10-08T10:00:00Z\npayload: go\n");

        assertThat(store.list("acme", HOME))
                .singleElement()
                .satisfies(s -> assertThat(s.due()).isEqualTo(Instant.parse("2026-10-08T10:00:00Z")));
    }

    @Test
    void list_ignoresAnUnusableRecurrence() {
        // '30 minutes' cannot be re-anchored; kept, it would fire on every
        // wakeup without ever moving on.
        givenDocument(
                TrillianScheduleStore.pathFor("standup"),
                "due: '2026-10-08T10:00:00Z'\nnext: 30 minutes\npayload: go\n");

        assertThat(store.list("acme", HOME)).isEmpty();
    }

    @Test
    void list_skipsDocumentsInSubFolders() {
        givenDocument("_vance/trillian/schedules/work/standup.yaml", "due: '2026-10-08T10:00:00Z'\npayload: go\n");

        assertThat(store.list("acme", HOME)).isEmpty();
    }

    @Test
    void parseEverySeconds_refusesAnOverflow() {
        assertThatThrownBy(() -> TrillianScheduleStore.parseEverySeconds("999999999999999d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    private void givenDocument(String path, String yaml) {
        DocumentDocument doc = DocumentDocument.builder().id("d1").path(path).build();
        when(documentService.listUnderFolder(eq("acme"), eq(HOME), anyString())).thenReturn(List.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }
}
