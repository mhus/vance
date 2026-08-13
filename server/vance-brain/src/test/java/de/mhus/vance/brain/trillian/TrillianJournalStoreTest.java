package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The journal is append-only and feeds a prompt, so the two things that
 * matter are that earlier entries survive a write and that its size stays
 * bounded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianJournalStoreTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String ACCOUNT = "_trillian-adam-4711";

    @Mock
    DocumentService documentService;

    @Test
    void theFirstEntry_getsTheExplainingHeader() {
        store().append(TENANT, PROJECT, ACCOUNT, "- first lesson");

        assertThat(written()).startsWith("# Trillian journal").contains("- first lesson");
    }

    @Test
    void aSecondEntry_keepsTheFirst() {
        // Append-only is the whole claim. A Trillian that may rewrite its
        // history can erase the entry that would have stopped it
        // repeating a mistake.
        givenStored("# Trillian journal\n\n- first lesson\n");

        store().append(TENANT, PROJECT, ACCOUNT, "- second lesson");

        assertThat(written()).contains("- first lesson").contains("- second lesson");
        assertThat(written().indexOf("- first lesson"))
                .isLessThan(written().indexOf("- second lesson"));
    }

    @Test
    void anEmptyEntry_isNotWritten() {
        store().append(TENANT, PROJECT, ACCOUNT, "   ");

        verify(documentService, never()).upsertText(anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any());
    }

    @Test
    void theTail_dropsTheHeader() {
        // The header explains the file to a human opening it and says
        // nothing to the Trillian; spending prompt on it is waste.
        givenStored(headerPlus("- a lesson"));

        assertThat(store().tail(TENANT, PROJECT, ACCOUNT))
                .isEqualTo("- a lesson")
                .doesNotContain("# Trillian journal");
    }

    @Test
    void anAbsentJournal_hasNoTail() {
        // null rather than "", so the caller omits the whole section
        // instead of rendering an empty heading.
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(store().tail(TENANT, PROJECT, ACCOUNT)).isNull();
    }

    @Test
    void aHeaderOnlyJournal_hasNoTail() {
        givenStored(headerPlus(""));

        assertThat(store().tail(TENANT, PROJECT, ACCOUNT)).isNull();
    }

    @Test
    void aLongJournal_isCappedAtAnEntryBoundary() {
        // It grows with every task and is rendered on every turn. Cutting
        // mid-line would hand the model half a sentence as if it were a
        // finished thought.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("- lesson number ").append(i).append('\n');
        }
        givenStored(headerPlus(sb.toString()));

        String tail = store().tail(TENANT, PROJECT, ACCOUNT);

        assertThat(tail).hasSizeLessThanOrEqualTo(TrillianJournalStore.PROMPT_BUDGET_CHARS);
        assertThat(tail).startsWith("- lesson number");
        // Newest kept, oldest dropped.
        assertThat(tail).contains("- lesson number 399").doesNotContain("- lesson number 0\n");
    }

    @Test
    void discardingRemovesTheFile() {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        when(documentService.findByPath(TENANT, PROJECT,
                TrillianJournalStore.pathFor(ACCOUNT))).thenReturn(Optional.of(doc));

        store().discard(TENANT, PROJECT, ACCOUNT);

        verify(documentService).delete(eq("doc-1"), any(WriteActor.class));
    }

    @Test
    void thePath_sitsBesideTheAttributes() {
        assertThat(TrillianJournalStore.pathFor(ACCOUNT))
                .isEqualTo("_vance/trillian/_trillian-adam-4711.journal.md");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private TrillianJournalStore store() {
        return new TrillianJournalStore(documentService);
    }

    private String headerPlus(String body) {
        return """
                # Trillian journal

                What this Trillian concluded after finishing tasks. Written by the
                Trillian itself, newest entries at the bottom. Read-only in practice —
                editing is possible but the value of a journal is that it was not
                rewritten afterwards.
                """ + "\n" + body;
    }

    private void givenStored(String text) {
        DocumentDocument doc = new DocumentDocument();
        when(documentService.findByPath(TENANT, PROJECT,
                TrillianJournalStore.pathFor(ACCOUNT))).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(text);
    }

    private String written() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(eq(TENANT), eq(PROJECT), anyString(), any(), any(),
                text.capture(), any(), any());
        return text.getValue();
    }
}
