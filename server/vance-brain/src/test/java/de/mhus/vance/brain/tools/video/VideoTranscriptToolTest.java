package de.mhus.vance.brain.tools.video;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptContent.Fragment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure helpers on {@link VideoTranscriptTool} — URL parsing, language
 * preference resolution, timestamp formatting, duration calculation.
 * The HTTP path (live YouTube call) is not exercised here; that's an
 * opt-in integration test.
 */
class VideoTranscriptToolTest {

    // ─── extractVideoId ─────────────────────────────────────────────

    @Test
    void extractVideoId_bareElevenCharIdPassesThrough() {
        assertThat(VideoTranscriptTool.extractVideoId("YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_watchUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube.com/watch?v=YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_watchUrlWithExtraParams() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube.com/watch?v=YNavwk7qk24&t=42s&list=PL123"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_shortUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://youtu.be/YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_shortUrlWithQuery() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://youtu.be/YNavwk7qk24?t=42"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_embedUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube.com/embed/YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_shortsUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube.com/shorts/YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_liveUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube.com/live/YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_mobileUrl() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://m.youtube.com/watch?v=YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_nocookieEmbed() {
        assertThat(VideoTranscriptTool.extractVideoId(
                "https://www.youtube-nocookie.com/embed/YNavwk7qk24"))
                .isEqualTo("YNavwk7qk24");
    }

    @Test
    void extractVideoId_garbageReturnsNull() {
        assertThat(VideoTranscriptTool.extractVideoId("not a url")).isNull();
        assertThat(VideoTranscriptTool.extractVideoId("")).isNull();
        assertThat(VideoTranscriptTool.extractVideoId(null)).isNull();
        assertThat(VideoTranscriptTool.extractVideoId("https://example.com/foo"))
                .isNull();
    }

    @Test
    void extractVideoId_idMustBeExactlyElevenChars() {
        assertThat(VideoTranscriptTool.extractVideoId("YNavwk7qk2"))    // 10
                .isNull();
        assertThat(VideoTranscriptTool.extractVideoId("YNavwk7qk244"))  // 12
                .isNull();
    }

    @Test
    void extractVideoId_trimsWhitespace() {
        assertThat(VideoTranscriptTool.extractVideoId("  YNavwk7qk24  "))
                .isEqualTo("YNavwk7qk24");
    }

    // ─── parseLanguages ─────────────────────────────────────────────

    @Test
    void parseLanguages_nullOrBlankFallsBackToDefault() {
        assertThat(VideoTranscriptTool.parseLanguages(null))
                .containsExactly("en", "de");
        assertThat(VideoTranscriptTool.parseLanguages(""))
                .containsExactly("en", "de");
        assertThat(VideoTranscriptTool.parseLanguages("   "))
                .containsExactly("en", "de");
    }

    @Test
    void parseLanguages_singleLanguage() {
        assertThat(VideoTranscriptTool.parseLanguages("de"))
                .containsExactly("de");
    }

    @Test
    void parseLanguages_commaSeparated() {
        assertThat(VideoTranscriptTool.parseLanguages("de,en,fr"))
                .containsExactly("de", "en", "fr");
    }

    @Test
    void parseLanguages_whitespaceSeparated() {
        assertThat(VideoTranscriptTool.parseLanguages("de en fr"))
                .containsExactly("de", "en", "fr");
    }

    @Test
    void parseLanguages_normalisesToLowercase() {
        assertThat(VideoTranscriptTool.parseLanguages("DE,En"))
                .containsExactly("de", "en");
    }

    // ─── secondsToHhmmss ────────────────────────────────────────────

    @Test
    void secondsToHhmmss_belowAMinute() {
        assertThat(VideoTranscriptTool.formatHhmmss(0)).isEqualTo("00:00:00");
        assertThat(VideoTranscriptTool.formatHhmmss(42)).isEqualTo("00:00:42");
    }

    @Test
    void secondsToHhmmss_minutesAndHours() {
        assertThat(VideoTranscriptTool.formatHhmmss(75)).isEqualTo("00:01:15");
        assertThat(VideoTranscriptTool.formatHhmmss(3725)).isEqualTo("01:02:05");
    }

    @Test
    void secondsToHhmmss_negativeFlooredToZero() {
        assertThat(VideoTranscriptTool.formatHhmmss(-5)).isEqualTo("00:00:00");
    }

    @Test
    void secondsToHhmmss_fractionsTruncatedDown() {
        assertThat(VideoTranscriptTool.formatHhmmss(42.9)).isEqualTo("00:00:42");
    }

    // ─── computeDurationSec ─────────────────────────────────────────

    @Test
    void computeDurationSec_emptyContentIsZero() {
        TranscriptContent empty = new TranscriptContent(List.of());
        assertThat(VideoTranscriptTool.computeDurationSec(empty))
                .isEqualTo(0.0);
    }

    @Test
    void computeDurationSec_lastFragmentEndWins() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("a", 0.0, 2.0),
                new Fragment("b", 2.0, 3.0),
                new Fragment("c", 5.0, 4.5)));
        assertThat(VideoTranscriptTool.computeDurationSec(c))
                .isEqualTo(9.5);
    }

    @Test
    void computeDurationSec_handlesOverlappingFragments() {
        // YouTube ASR sometimes emits overlapping segments. The
        // duration must reflect the largest end-time, not the sum of
        // per-fragment durations.
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("a", 0.0, 5.0),
                new Fragment("b", 3.0, 5.0),    // overlaps a
                new Fragment("c", 4.0, 2.0)));  // ends before b
        assertThat(VideoTranscriptTool.computeDurationSec(c))
                .isEqualTo(8.0);
    }

    // ─── formatPlain / formatWithTimestamps ─────────────────────────

    @Test
    void formatPlain_joinsFragmentsWithNewlines() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("Hello", 0.0, 1.0),
                new Fragment("world", 1.0, 1.0)));
        assertThat(VideoTranscriptTool.formatPlain(c))
                .isEqualTo("Hello\nworld");
    }

    @Test
    void formatPlain_skipsEmptyAndWhitespaceFragments() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("real", 0.0, 1.0),
                new Fragment("   ", 1.0, 1.0),
                new Fragment("", 2.0, 1.0),
                new Fragment("text", 3.0, 1.0)));
        assertThat(VideoTranscriptTool.formatPlain(c))
                .isEqualTo("real\ntext");
    }

    @Test
    void formatPlain_decodesHtmlEntities() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("Tom &amp; Jerry", 0.0, 1.0),
                new Fragment("&quot;quoted&quot;", 1.0, 1.0)));
        assertThat(VideoTranscriptTool.formatPlain(c))
                .isEqualTo("Tom & Jerry\n\"quoted\"");
    }

    @Test
    void formatPlain_collapsesInternalWhitespace() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("multi   spaced\n\nlines", 0.0, 1.0)));
        assertThat(VideoTranscriptTool.formatPlain(c))
                .isEqualTo("multi spaced lines");
    }

    @Test
    void formatWithTimestamps_prefixesEachFragment() {
        TranscriptContent c = new TranscriptContent(List.of(
                new Fragment("First", 0.0, 1.0),
                new Fragment("Second", 75.5, 2.0)));
        assertThat(VideoTranscriptTool.formatWithTimestamps(c))
                .isEqualTo("[00:00:00] First\n[00:01:15] Second");
    }

    // ─── FallbackMode.parse ─────────────────────────────────────────

    @Test
    void fallbackMode_nullOrBlankDefaultsToAuto() {
        assertThat(VideoTranscriptTool.FallbackMode.parse(null))
                .isEqualTo(VideoTranscriptTool.FallbackMode.AUTO);
        assertThat(VideoTranscriptTool.FallbackMode.parse(""))
                .isEqualTo(VideoTranscriptTool.FallbackMode.AUTO);
        assertThat(VideoTranscriptTool.FallbackMode.parse("  "))
                .isEqualTo(VideoTranscriptTool.FallbackMode.AUTO);
    }

    @Test
    void fallbackMode_recognisedTokens() {
        assertThat(VideoTranscriptTool.FallbackMode.parse("auto"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.AUTO);
        assertThat(VideoTranscriptTool.FallbackMode.parse("captions"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.CAPTIONS_ONLY);
        assertThat(VideoTranscriptTool.FallbackMode.parse("asr"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.ASR_ONLY);
    }

    @Test
    void fallbackMode_caseInsensitive() {
        assertThat(VideoTranscriptTool.FallbackMode.parse("CAPTIONS"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.CAPTIONS_ONLY);
        assertThat(VideoTranscriptTool.FallbackMode.parse("Asr"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.ASR_ONLY);
    }

    @Test
    void fallbackMode_unknownFallsBackToAuto() {
        assertThat(VideoTranscriptTool.FallbackMode.parse("nonsense"))
                .isEqualTo(VideoTranscriptTool.FallbackMode.AUTO);
    }
}
