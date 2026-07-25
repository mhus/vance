package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HotPathMarkerDetectorTest {

    private final HotPathMarkerDetector detector = new HotPathMarkerDetector();

    @Test
    void detect_findsFutureRuleMarker() {
        var matches = detector.detect("Ab jetzt nur committen wenn ich frage", "de");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("ab jetzt");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.FUTURE_RULE);
    }

    @Test
    void detect_findsMemorizeMarker() {
        var matches = detector.detect("Merk dir das für später", "de");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("merk dir");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.MEMORIZE);
    }

    @Test
    void detect_findsForgetMarker() {
        var matches = detector.detect("Vergiss die Commit-Regel", "de");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("vergiss");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.FORGET);
    }

    @Test
    void detect_findsRevokeMarker() {
        var matches = detector.detect("Mach das nicht mehr so", "de");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("nicht mehr");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.REVOKE);
    }

    @Test
    void detect_findsEnglishMarkers() {
        assertThat(detector.detect("From now on always run the tests", "en"))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FUTURE_RULE);
        assertThat(detector.detect("Forget that rule", "en"))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FORGET);
    }

    @Test
    void detect_isCaseInsensitive() {
        assertThat(detector.detect("AB JETZT", "de").stream())
                .anyMatch(m -> m.marker().equals("ab jetzt"));
        assertThat(detector.detect("merk DIR", "de").stream())
                .anyMatch(m -> m.marker().equals("merk dir"));
    }

    @Test
    void detect_respectsWordBoundariesForSingleWordMarkers() {
        // "vergiss" must not match inside "vergisslich"
        assertThat(detector.detect("Ich bin vergisslich", "de")).isEmpty();
    }

    @Test
    void detect_handlesGermanUmlautInMarker() {
        // "künftig" contains 'ü' — UNICODE_CHARACTER_CLASS must handle the boundary.
        assertThat(detector.detect("Bitte künftig anders verfahren", "de"))
                .extracting(MarkerMatch::marker)
                .containsExactly("künftig");
    }

    @Test
    void detect_returnsEmptyForCleanText() {
        assertThat(detector.detect("Schau dir mal foo.java an", "de")).isEmpty();
    }

    @Test
    void detect_returnsEmptyForBlankText() {
        assertThat(detector.detect("", "de")).isEmpty();
        assertThat(detector.detect("   ", "de")).isEmpty();
    }

    @Test
    void detect_findsMultipleMarkersInPositionOrder() {
        var matches = detector.detect(
                "Vergiss die alte Regel — ab jetzt machen wir das anders", "de");

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).marker()).isEqualTo("vergiss");
        assertThat(matches.get(1).marker()).isEqualTo("ab jetzt");
        assertThat(matches.get(0).position()).isLessThan(matches.get(1).position());
    }

    // ─── language semantics ───

    @Test
    void detect_englishBaselineMatchesEvenWhenLangIsNull() {
        assertThat(detector.detect("Forget that rule", null))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FORGET);
    }

    @Test
    void detect_englishBaselineMatchesEvenForUnrelatedLang() {
        // "fr" has no catalogue entries, but the en baseline still fires.
        assertThat(detector.detect("Forget that rule", "fr"))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FORGET);
    }

    @Test
    void detect_germanPhraseMatchesOnlyWhenLangIsGerman() {
        assertThat(detector.detect("Vergiss die Regel", "de"))
                .extracting(MarkerMatch::marker)
                .containsExactly("vergiss");
    }

    @Test
    void detect_germanPhraseDoesNotMatchUnderEnglishBaselineOnly() {
        // lang=null → en baseline only → no German phrase.
        assertThat(detector.detect("Vergiss die Regel", null)).isEmpty();
        // Explicit "en" behaves the same as the baseline.
        assertThat(detector.detect("Vergiss die Regel", "en")).isEmpty();
    }

    @Test
    void detect_unknownLanguageFallsBackToEnglishBaselineOnly() {
        // German content + a language with no catalogue entries → nothing,
        // because only en is active and the phrase is not English.
        assertThat(detector.detect("Vergiss die Regel", "fr")).isEmpty();
    }

    @Test
    void detect_normalizesRegionSubtagToPrimaryLanguage() {
        // "de-DE" must resolve to the "de" catalogue entry.
        assertThat(detector.detect("Vergiss die Regel", "de-DE"))
                .extracting(MarkerMatch::marker)
                .containsExactly("vergiss");
    }

    // ─── hasMarker ───

    @Test
    void hasMarker_trueWhenMarkerPresent() {
        assertThat(detector.hasMarker("ab jetzt anders", "de")).isTrue();
    }

    @Test
    void hasMarker_falseWhenNoMarker() {
        assertThat(detector.hasMarker("schau dir mal foo.java an", "de")).isFalse();
    }

    @Test
    void hasMarker_falseForBlankInput() {
        assertThat(detector.hasMarker("", "de")).isFalse();
        assertThat(detector.hasMarker("    ", "de")).isFalse();
    }

    @Test
    void hasMarker_englishBaselineFiresWithNullLang() {
        assertThat(detector.hasMarker("please forget the rule", null)).isTrue();
    }

    @Test
    void hasMarker_germanPhraseNeedsGermanLang() {
        assertThat(detector.hasMarker("vergiss die Regel", null)).isFalse();
        assertThat(detector.hasMarker("vergiss die Regel", "de")).isTrue();
    }
}
