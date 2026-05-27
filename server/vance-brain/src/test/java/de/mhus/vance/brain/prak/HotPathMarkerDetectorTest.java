package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HotPathMarkerDetectorTest {

    private final HotPathMarkerDetector detector = new HotPathMarkerDetector();

    @Test
    void detect_findsFutureRuleMarker() {
        var matches = detector.detect("Ab jetzt nur committen wenn ich frage");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("ab jetzt");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.FUTURE_RULE);
    }

    @Test
    void detect_findsMemorizeMarker() {
        var matches = detector.detect("Merk dir das für später");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("merk dir");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.MEMORIZE);
    }

    @Test
    void detect_findsForgetMarker() {
        var matches = detector.detect("Vergiss die Commit-Regel");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("vergiss");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.FORGET);
    }

    @Test
    void detect_findsRevokeMarker() {
        var matches = detector.detect("Mach das nicht mehr so");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).marker()).isEqualTo("nicht mehr");
        assertThat(matches.get(0).category()).isEqualTo(MarkerCategory.REVOKE);
    }

    @Test
    void detect_findsEnglishMarkers() {
        assertThat(detector.detect("From now on always run the tests"))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FUTURE_RULE);
        assertThat(detector.detect("Forget that rule"))
                .extracting(MarkerMatch::category)
                .contains(MarkerCategory.FORGET);
    }

    @Test
    void detect_isCaseInsensitive() {
        assertThat(detector.detect("AB JETZT").stream())
                .anyMatch(m -> m.marker().equals("ab jetzt"));
        assertThat(detector.detect("merk DIR").stream())
                .anyMatch(m -> m.marker().equals("merk dir"));
    }

    @Test
    void detect_respectsWordBoundariesForSingleWordMarkers() {
        // "vergiss" must not match inside "vergisslich"
        assertThat(detector.detect("Ich bin vergisslich")).isEmpty();
    }

    @Test
    void detect_handlesGermanUmlautInMarker() {
        // "künftig" contains 'ü' — UNICODE_CHARACTER_CLASS must handle the boundary.
        assertThat(detector.detect("Bitte künftig anders verfahren"))
                .extracting(MarkerMatch::marker)
                .containsExactly("künftig");
    }

    @Test
    void detect_returnsEmptyForCleanText() {
        assertThat(detector.detect("Schau dir mal foo.java an")).isEmpty();
    }

    @Test
    void detect_returnsEmptyForBlankText() {
        assertThat(detector.detect("")).isEmpty();
        assertThat(detector.detect("   ")).isEmpty();
    }

    @Test
    void detect_findsMultipleMarkersInPositionOrder() {
        var matches = detector.detect(
                "Vergiss die alte Regel — ab jetzt machen wir das anders");

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).marker()).isEqualTo("vergiss");
        assertThat(matches.get(1).marker()).isEqualTo("ab jetzt");
        assertThat(matches.get(0).position()).isLessThan(matches.get(1).position());
    }

    @Test
    void hasMarker_trueWhenMarkerPresent() {
        assertThat(detector.hasMarker("ab jetzt anders")).isTrue();
    }

    @Test
    void hasMarker_falseWhenNoMarker() {
        assertThat(detector.hasMarker("schau dir mal foo.java an")).isFalse();
    }

    @Test
    void hasMarker_falseForBlankInput() {
        assertThat(detector.hasMarker("")).isFalse();
        assertThat(detector.hasMarker("    ")).isFalse();
    }
}
