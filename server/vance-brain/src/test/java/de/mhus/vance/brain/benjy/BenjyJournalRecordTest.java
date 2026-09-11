package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the journal record's markdown form (§4b): the journal is chat history
 * rendered by both surfaces (web: MarkdownView, foot: MarkdownAnsiRenderer),
 * and nothing parses it back — so the record is styled for the eye: bold
 * record head, the open worklist as inline-code chips behind a bold
 * {@code Open:} label.
 */
class BenjyJournalRecordTest {

    @Test
    void boldsTheRecordHead_upToTheFirstColon() {
        String record = BenjyEngine.journalRecord("close #2: item completed", List.of());

        assertThat(record).isEqualTo("[benjy] **close #2:** item completed");
    }

    @Test
    void headlessEntries_stayPlain() {
        String record = BenjyEngine.journalRecord("dropped unknown task type mystery", List.of());

        assertThat(record).isEqualTo("[benjy] dropped unknown task type mystery");
    }

    @Test
    void rendersTheOpenWorklist_asCodeChips() {
        String record = BenjyEngine.journalRecord(
                "close #2: item completed", List.of("close_item #3", "close_item #4", "route"));

        assertThat(record)
                .isEqualTo("[benjy] **close #2:** item completed"
                        + "\n\n**Open:** `close_item #3` · `close_item #4` · `route`");
    }

    @Test
    void onlyTheFirstColonFormsTheHead() {
        // route reasons can contain colons — the head is stage + item ref,
        // everything after the FIRST ": " stays plain text.
        String record = BenjyEngine.journalRecord("route: retry — verdict: fail", List.of());

        assertThat(record).isEqualTo("[benjy] **route:** retry — verdict: fail");
    }
}
