package de.mhus.vance.liver.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.liver.manifest.ManifestEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class HistoryCommandTest {

    @Test
    void differentHash_isAChange() {
        assertThat(HistoryCommand.contentChanged(entry("v1", "aaa", 10, 1L), entry("v1", "bbb", 10, 1L)))
                .isTrue();
    }

    @Test
    void sameHash_isNoChange_evenWithANewVersion() {
        assertThat(HistoryCommand.contentChanged(entry("v1", "aaa", 10, 1L), entry("v2", "aaa", 10, 2L)))
                .isFalse();
    }

    @Test
    void newContentVersion_withoutHashes_isAChange() {
        assertThat(HistoryCommand.contentChanged(entry("v1", null, 10, 1L), entry("v2", null, 10, 1L)))
                .isTrue();
    }

    @Test
    void missingContentVersion_fallsBackToSizeAndUpdatedAt() {
        // Older manifests carry no version: null equals null must not hide an edit.
        assertThat(HistoryCommand.contentChanged(entry(null, null, 10, 1L), entry(null, null, 12, 1L)))
                .isTrue();
        assertThat(HistoryCommand.contentChanged(entry(null, null, 10, 1L), entry(null, null, 10, 2L)))
                .isTrue();
        assertThat(HistoryCommand.contentChanged(entry(null, null, 10, 1L), entry(null, null, 10, 1L)))
                .isFalse();
    }

    @Test
    void inlineDocument_versionIsTheId_fallsBackToSizeAndUpdatedAt() {
        assertThat(HistoryCommand.contentChanged(entry("id-1", null, 10, 1L), entry("id-1", null, 10, 2L)))
                .isTrue();
    }

    private static ManifestEntry entry(
            @Nullable String contentVersion, @Nullable String sha256, long size, @Nullable Long updatedAtMs) {
        return new ManifestEntry(
                "wiki",
                "a.md",
                "projects/wiki/a.md",
                "id-1",
                "lin-1",
                "markdown",
                "Title",
                "text/markdown",
                size,
                1700000000000L,
                updatedAtMs,
                contentVersion,
                false,
                sha256,
                ManifestEntry.Stored.LINKED);
    }
}
