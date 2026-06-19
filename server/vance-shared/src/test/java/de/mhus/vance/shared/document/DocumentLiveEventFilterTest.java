package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Nails down which paths fire a {@link DocumentLiveChangedEvent}. The
 * filter is intentionally wider than {@code isEventPublishable} —
 * user-facing edits under {@code documents/...} must reach WS
 * subscribers even though they do not feed brain-internal caches.
 */
class DocumentLiveEventFilterTest {

    @Test
    void userDocumentPaths_arePublishable() {
        assertThat(DocumentService.isLiveEventPublishable("documents/notes.md")).isTrue();
        assertThat(DocumentService.isLiveEventPublishable("documents/sub/dir/file.txt")).isTrue();
    }

    @Test
    void configPaths_arePublishable() {
        assertThat(DocumentService.isLiveEventPublishable("_vance/server-tools/foo.yaml")).isTrue();
        assertThat(DocumentService.isLiveEventPublishable("_vance/setting_forms/bar.yaml")).isTrue();
    }

    @Test
    void noisePrefixes_areNotPublishable() {
        assertThat(DocumentService.isLiveEventPublishable("_vance/logs/scheduler-2026-06-19.json")).isFalse();
        assertThat(DocumentService.isLiveEventPublishable("_bin/trashed.md")).isFalse();
        assertThat(DocumentService.isLiveEventPublishable("_slart/scratch.md")).isFalse();
        assertThat(DocumentService.isLiveEventPublishable("_chatbox/upload-abc.bin")).isFalse();
    }

    @Test
    void nullPath_isNotPublishable() {
        assertThat(DocumentService.isLiveEventPublishable(null)).isFalse();
    }
}
