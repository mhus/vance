package de.mhus.vance.brain.webdav;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebDavPropertiesTest {

    private final WebDavProperties properties = new WebDavProperties();

    @Test
    void isSidecar_matchesExactAndWildcardPatterns() {
        assertThat(properties.isSidecar(".DS_Store")).isTrue();
        assertThat(properties.isSidecar("._resourcefork")).isTrue();
        assertThat(properties.isSidecar("Thumbs.db")).isTrue();
        assertThat(properties.isSidecar("desktop.ini")).isTrue();
    }

    @Test
    void isSidecar_leavesRealDocumentsAlone() {
        assertThat(properties.isSidecar("notes.md")).isFalse();
        assertThat(properties.isSidecar("_index.md")).isFalse();
        assertThat(properties.isSidecar("report.docx")).isFalse();
    }

    @Test
    void isHidden_coversSidecarsAndFolderMarker() {
        assertThat(properties.isHidden(".vancedir")).isTrue();
        assertThat(properties.isHidden(".DS_Store")).isTrue();
    }

    @Test
    void isHidden_doesNotHideSystemPrefixesOrRealFiles() {
        // §8.3 — _-prefixed system paths stay visible in v1.
        assertThat(properties.isHidden("_bin")).isFalse();
        assertThat(properties.isHidden("_vance")).isFalse();
        assertThat(properties.isHidden("a.md")).isFalse();
    }
}
