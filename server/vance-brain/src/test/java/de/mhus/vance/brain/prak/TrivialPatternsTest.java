package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Language-aware trivial-turn detection: English baseline is always
 * active, the resolved {@code chat.language} adds its phrases on top.
 * Backed by the real bundled {@code keywords/trivial-patterns.yaml}.
 */
class TrivialPatternsTest {

    private final TrivialPatterns patterns = new TrivialPatterns();

    // ─── ACK ───

    @Test
    void isAck_englishBaselineMatchesWhenLangNull() {
        assertThat(patterns.isAck("ok", null)).isTrue();
        assertThat(patterns.isAck("thanks", null)).isTrue();
    }

    @Test
    void isAck_englishBaselineMatchesEvenForOtherLang() {
        // "fr" adds French phrases but the en baseline still fires.
        assertThat(patterns.isAck("ok", "fr")).isTrue();
    }

    @Test
    void isAck_germanPhraseMatchesOnlyWhenLangIsGerman() {
        assertThat(patterns.isAck("verstanden", "de")).isTrue();
        // Under the English baseline only, the German phrase is silent.
        assertThat(patterns.isAck("verstanden", null)).isFalse();
        assertThat(patterns.isAck("verstanden", "en")).isFalse();
        assertThat(patterns.isAck("verstanden", "fr")).isFalse();
    }

    @Test
    void isAck_frenchPhraseMatchesOnlyWhenLangIsFrench() {
        assertThat(patterns.isAck("d'accord", "fr")).isTrue();
        assertThat(patterns.isAck("d'accord", null)).isFalse();
        assertThat(patterns.isAck("d'accord", "de")).isFalse();
    }

    @Test
    void isAck_toleratesTrailingPunctuation() {
        assertThat(patterns.isAck("danke!", "de")).isTrue();
        assertThat(patterns.isAck("got it.", null)).isTrue();
    }

    @Test
    void isAck_rejectsLongerSentence() {
        assertThat(patterns.isAck("ok, dann machen wir das so", "de")).isFalse();
        assertThat(patterns.isAck("yes I fully agree with that plan", null)).isFalse();
    }

    @Test
    void isAck_falseForBlankInput() {
        assertThat(patterns.isAck("", "de")).isFalse();
        assertThat(patterns.isAck("   ", "de")).isFalse();
    }

    // ─── SELF_NARRATION ───

    @Test
    void isSelfNarration_englishPrefixMatchesRegardlessOfLang() {
        assertThat(patterns.isSelfNarration("Let me check that file", null)).isTrue();
        assertThat(patterns.isSelfNarration("I'll now read the config", "de")).isTrue();
    }

    @Test
    void isSelfNarration_germanPrefixMatchesOnlyWhenLangIsGerman() {
        assertThat(patterns.isSelfNarration("Ich werde jetzt foo.java lesen", "de")).isTrue();
        assertThat(patterns.isSelfNarration("Ich werde jetzt foo.java lesen", null)).isFalse();
        assertThat(patterns.isSelfNarration("Ich werde jetzt foo.java lesen", "en")).isFalse();
    }

    @Test
    void isSelfNarration_rejectsRealStatement() {
        assertThat(patterns.isSelfNarration("Der Code verwendet JSpecify", "de")).isFalse();
        assertThat(patterns.isSelfNarration("The codebase is consistent", null)).isFalse();
    }

    // ─── language-tag normalization ───

    @Test
    void normalizesRegionSubtagToPrimaryLanguage() {
        // "de-DE" must resolve to the "de" catalogue entry.
        assertThat(patterns.isAck("verstanden", "de-DE")).isTrue();
        assertThat(patterns.isSelfNarration("Lass mich kurz überlegen", "de-DE")).isTrue();
    }

    @Test
    void isCaseInsensitive() {
        assertThat(patterns.isAck("OK", null)).isTrue();
        assertThat(patterns.isAck("Verstanden", "de")).isTrue();
        assertThat(patterns.isSelfNarration("LET ME check", null)).isTrue();
    }
}
