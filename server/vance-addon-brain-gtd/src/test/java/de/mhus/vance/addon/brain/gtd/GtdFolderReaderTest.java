package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/** Pure helpers of {@link GtdFolderReader} + {@link GtdBucket} wire mapping. */
class GtdFolderReaderTest {

    @Test
    void slugify_normalises() {
        assertThat(GtdFolderReader.slugify("Call the Accountant!")).isEqualTo("call-the-accountant");
        assertThat(GtdFolderReader.slugify("  ")).isEmpty();
    }

    @Test
    void normaliseFolder_stripsSlashes_rejectsEmpty() {
        assertThat(GtdFolderReader.normaliseFolder("/gtd/")).isEqualTo("gtd");
        assertThatThrownBy(() -> GtdFolderReader.normaliseFolder(" "))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void bucket_wireRoundTrip() {
        assertThat(GtdBucket.TODAY.wireName()).isEqualTo("today");
        assertThat(GtdBucket.fromWire("upcoming")).isEqualTo(GtdBucket.UPCOMING);
        assertThat(GtdBucket.fromWire("nonsense")).isNull();
        assertThat(GtdBucket.fromWire(null)).isNull();
    }
}
