package de.mhus.vance.addon.brain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/** Pure helpers of {@link JournalFolderReader} — date extraction + folder normalisation. */
class JournalFolderReaderTest {

    @Test
    void dateFromLeaf_extractsIsoPrefix() {
        assertThat(JournalFolderReader.dateFromLeaf("2026-07-24.md")).isEqualTo("2026-07-24");
        assertThat(JournalFolderReader.dateFromLeaf("2026-07-24-evening.md")).isEqualTo("2026-07-24");
    }

    @Test
    void dateFromLeaf_rejectsNonDateOrInvalid() {
        assertThat(JournalFolderReader.dateFromLeaf("notes.md")).isNull();
        assertThat(JournalFolderReader.dateFromLeaf("2026-13-40.md")).isNull();
    }

    @Test
    void humaniseDate_longFormatsValidDates_echoesInvalid() {
        assertThat(JournalFolderReader.humaniseDate("2026-07-24")).isEqualTo("July 24, 2026");
        assertThat(JournalFolderReader.humaniseDate("garbage")).isEqualTo("garbage");
    }

    @Test
    void normaliseFolder_stripsSlashes_andRejectsEmpty() {
        assertThat(JournalFolderReader.normaliseFolder("/diary/")).isEqualTo("diary");
        assertThatThrownBy(() -> JournalFolderReader.normaliseFolder("  "))
                .isInstanceOf(ToolException.class);
    }
}
