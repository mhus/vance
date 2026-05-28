package de.mhus.vance.brain.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OfficeDocumentKeyTest {

    @Test
    void build_combinesIdAndVersion() {
        assertThat(OfficeDocumentKey.build("abc123", 4L))
                .isEqualTo("abc123-v4");
    }

    @Test
    void build_nullVersionDefaultsToZero() {
        assertThat(OfficeDocumentKey.build("abc123", null))
                .isEqualTo("abc123-v0");
    }

    @Test
    void build_versionBumpsKey() {
        // The whole point: same docId, different version → different key.
        assertThat(OfficeDocumentKey.build("abc", 1L))
                .isNotEqualTo(OfficeDocumentKey.build("abc", 2L));
    }

    @Test
    void build_blankDocIdRejected() {
        assertThatThrownBy(() -> OfficeDocumentKey.build(null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docId required");
        assertThatThrownBy(() -> OfficeDocumentKey.build("", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficeDocumentKey.build("   ", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
