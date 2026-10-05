package de.mhus.vance.brain.hotblack;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotblackServiceHelpersTest {

    // ──── slugify ──────────────────────────────────────────────────────

    @Test
    void slugify_lowercases_and_kebabs() {
        assertThat(HotblackService.slugify("Watercolor Cat on Moon")).isEqualTo("watercolor-cat-on-moon");
    }

    @Test
    void slugify_strips_diacritics() {
        assertThat(HotblackService.slugify("Böhse Onkelz über Nacht")).isEqualTo("bohse-onkelz-uber-nacht");
    }

    @Test
    void slugify_clamps_to_thirty_chars() {
        String slug = HotblackService.slugify("a very long title that goes on and on and on");
        assertThat(slug).hasSizeLessThanOrEqualTo(30).doesNotEndWith("-");
    }

    @Test
    void slugify_falls_back_to_audio_for_empty_input() {
        assertThat(HotblackService.slugify("!!!")).isEqualTo("audio");
        assertThat(HotblackService.slugify(null)).isEqualTo("audio");
    }

    // ──── effectiveCostUsd ─────────────────────────────────────────────

    @Test
    void vendor_reported_cost_header_wins() {
        DocumentDocument committed = docWithHeader("costUsd", "0.04");

        assertThat(HotblackService.effectiveCostUsd(0.001, committed)).isEqualTo(0.04);
    }

    @Test
    void estimate_is_used_when_no_vendor_cost_is_reported() {
        DocumentDocument committed = docWithHeader("model", "openrouter:google/lyria-3-clip-preview");

        assertThat(HotblackService.effectiveCostUsd(0.002, committed)).isEqualTo(0.002);
    }

    @Test
    void unpriced_stays_unpriced() {
        DocumentDocument committed = docWithHeader("model", "local:faster-whisper-small");

        assertThat(HotblackService.effectiveCostUsd(null, committed)).isNull();
    }

    @Test
    void non_numeric_cost_header_falls_back_to_the_estimate() {
        DocumentDocument committed = docWithHeader("costUsd", "n/a");

        assertThat(HotblackService.effectiveCostUsd(0.5, committed)).isEqualTo(0.5);
    }

    private static DocumentDocument docWithHeader(String key, String value) {
        DocumentDocument doc = new DocumentDocument();
        doc.setHeaders(Map.of(key, value));
        return doc;
    }
}
