package de.mhus.vance.brain.hotblack;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.Map;
import java.util.Set;
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

    // ──── audio format gate ────────────────────────────────────────────

    @Test
    void pcm_serving_model_delivers_the_wav_document_format() {
        assertThat(HotblackService.acceptsAudioFormat(Set.of("pcm"), "wav")).isTrue();
        assertThat(HotblackService.acceptsAudioFormat(Set.of("pcm"), "mp3")).isFalse();
    }

    @Test
    void format_names_match_case_insensitively() {
        assertThat(HotblackService.acceptsAudioFormat(Set.of("MP3", "PCM"), "mp3"))
                .isTrue();
        assertThat(HotblackService.acceptsAudioFormat(Set.of("PCM"), "wav")).isTrue();
    }

    @Test
    void default_format_prefers_the_configured_one_when_the_model_serves_it() {
        assertThat(HotblackService.chooseOutputFormat(Set.of("mp3", "wav"), "wav"))
                .isEqualTo("wav");
    }

    @Test
    void default_format_downshifts_to_a_servable_standard_format() {
        // One scope holds many models — a pcm-only TTS must still work under
        // the global `mp3` default, delivering the wav document form.
        assertThat(HotblackService.chooseOutputFormat(Set.of("pcm"), "mp3")).isEqualTo("wav");
        assertThat(HotblackService.chooseOutputFormat(Set.of("wav"), "mp3")).isEqualTo("wav");
        assertThat(HotblackService.chooseOutputFormat(Set.of("wav"), null)).isEqualTo("wav");
    }

    @Test
    void default_format_is_absent_when_the_model_serves_no_standard_format() {
        assertThat(HotblackService.chooseOutputFormat(Set.of("ogg"), "mp3")).isNull();
    }

    private static DocumentDocument docWithHeader(String key, String value) {
        DocumentDocument doc = new DocumentDocument();
        doc.setHeaders(Map.of(key, value));
        return doc;
    }
}
