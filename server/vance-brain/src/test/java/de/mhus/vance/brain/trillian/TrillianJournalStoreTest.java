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

        assertThat(written()).startsWith("# Trillian journal").contains("first lesson");
    }

    @Test
    void aSecondEntry_keepsTheFirst() {
        // Append-only is the whole claim. A Trillian that may rewrite its
        // history can erase the entry that would have stopped it
        // repeating a mistake.
        givenStored("# Trillian journal\n\n- first lesson\n");

        store().append(TENANT, PROJECT, ACCOUNT, "- second lesson");

        assertThat(written()).contains("- first lesson").contains("second lesson");
        assertThat(written().indexOf("- first lesson"))
                .isLessThan(written().indexOf("second lesson"));
    }

    @Test
    void everyEntry_carriesTheDateItWasWritten() {
        // Stamped by the store, not asked of the model: the reflexion
        // pass has no reliable notion of today, and an invented date
        // would make a stale note look fresh.
        store().append(TENANT, PROJECT, ACCOUNT, "- reports/ is locked for AI");

        assertThat(written()).containsPattern("- \\d{4}-\\d{2}-\\d{2}: reports/ is locked for AI");
    }

    @Test
    void anEntryThatAlreadyHasADate_isNotStampedTwice() {
        store().append(TENANT, PROJECT, ACCOUNT, "- 2026-01-02: something old");

        assertThat(written()).contains("- 2026-01-02: something old");
        assertThat(written()).doesNotContainPattern("- \\d{4}-\\d{2}-\\d{2}: 2026-01-02");
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

    @Test
    void pruning_removesExactlyTheNumberedEntry() {
        givenStored(headerPlus("- one\n- two\n- three\n"));

        store().removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(2));

        assertThat(written()).contains("- one").contains("- three").doesNotContain("- two");
    }

    @Test
    void pruning_keepsTheOtherLinesOfAMultiLineEntry() {
        // Nothing forbids an entry from running over several lines, and
        // rebuilding the file from its "- " lines alone would silently
        // delete every continuation in it.
        givenStored(headerPlus("- one\n  with a second line\n- two\n"));

        store().removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(2));

        assertThat(written()).contains("with a second line").doesNotContain("- two");
    }

    @Test
    void pruning_keepsWhatAHumanWroteBetweenTheEntries() {
        // The header says editing by hand is fine, so a prune that drops
        // everything that is not an entry is a deletion nobody asked for.
        givenStored(headerPlus("- one\n\nNote from me: keep an eye on this.\n\n- two\n"));

        store().removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(2));

        assertThat(written()).contains("Note from me").doesNotContain("- two");
    }

    @Test
    void anEntryIsWhatItSpansUpToTheNextOne() {
        // The numbering the model answers against is this list, so a
        // continuation line may not shift the positions.
        givenStored(headerPlus("- one\n  continued\n- two\n"));

        assertThat(store().entries(TENANT, PROJECT, ACCOUNT))
                .containsExactly("- one\n  continued", "- two");
    }

    @Test
    void pruningNothingThatMatches_writesNothing() {
        givenStored(headerPlus("- one\n"));

        store().removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(7));

        verify(documentService, never()).upsertText(anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any());
    }

    /** Uses the store's own header — a copy here broke the moment it changed. */
    private String headerPlus(String body) {
        return TrillianJournalStore.HEADER + "\n" + body;
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
