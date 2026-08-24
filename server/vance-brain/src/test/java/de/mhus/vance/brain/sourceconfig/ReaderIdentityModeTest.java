package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReaderIdentityModeTest {

    @Test
    void parse_absentValue_fallsBack() {
        assertThat(ReaderIdentityMode.parse(null, ReaderIdentityMode.NONE))
                .isEqualTo(ReaderIdentityMode.NONE);
    }

    @Test
    void parse_knownWordInAnyCase_isRecognised() {
        assertThat(ReaderIdentityMode.parse("  PseudoNym ", ReaderIdentityMode.NONE))
                .isEqualTo(ReaderIdentityMode.PSEUDONYM);
    }

    @Test
    void parse_unknownWord_fallsBackRatherThanThrowing() {
        // A privacy control has to fail towards less, not towards more: a typo
        // must never be the reason a login leaves the house.
        assertThat(ReaderIdentityMode.parse("pseudonyme", ReaderIdentityMode.NONE))
                .isEqualTo(ReaderIdentityMode.NONE);
    }

    @Test
    void isKnown_separatesTypoFromAbsence() {
        assertThat(ReaderIdentityMode.isKnown(null)).isFalse();
        assertThat(ReaderIdentityMode.isKnown("identity")).isTrue();
        assertThat(ReaderIdentityMode.isKnown("identitiy")).isFalse();
    }

    @Test
    void atMost_capsDownwardsAndNeverWidens() {
        assertThat(ReaderIdentityMode.IDENTITY.atMost(ReaderIdentityMode.PSEUDONYM))
                .isEqualTo(ReaderIdentityMode.PSEUDONYM);
        assertThat(ReaderIdentityMode.NONE.atMost(ReaderIdentityMode.IDENTITY))
                .isEqualTo(ReaderIdentityMode.NONE);
        assertThat(ReaderIdentityMode.PSEUDONYM.atMost(ReaderIdentityMode.PSEUDONYM))
                .isEqualTo(ReaderIdentityMode.PSEUDONYM);
    }

    @Test
    void declarationOrder_isMonotoneInWhatLeavesTheHouse() {
        // atMost is a plain minimum only as long as this holds; a value
        // inserted in the wrong place would silently invert a ceiling.
        assertThat(ReaderIdentityMode.values()).containsExactly(
                ReaderIdentityMode.NONE,
                ReaderIdentityMode.PSEUDONYM,
                ReaderIdentityMode.IDENTITY);
    }

    @Test
    void sourceConfig_withoutTheField_isNone() {
        assertThat(config(Map.of()).readerIdentity()).isEqualTo(ReaderIdentityMode.NONE);
        assertThat(config(Map.of()).hasUnknownReaderIdentity()).isFalse();
    }

    @Test
    void sourceConfig_withMisspelledField_reportsItSeparately() {
        SourceConfig config = config(Map.of(ReaderIdentityMode.FIELD, "identitiy"));

        assertThat(config.readerIdentity()).isEqualTo(ReaderIdentityMode.NONE);
        assertThat(config.hasUnknownReaderIdentity()).isTrue();
    }

    @Test
    void sourceConfig_withReaderIdentity_roundTripsThroughWith() {
        SourceConfig capped = config(Map.of(ReaderIdentityMode.FIELD, "identity"))
                .withReaderIdentity(ReaderIdentityMode.PSEUDONYM);

        assertThat(capped.readerIdentity()).isEqualTo(ReaderIdentityMode.PSEUDONYM);
        assertThat(capped.name()).isEqualTo("alpha");
    }

    @Test
    void sourceConfig_cacheDefaultsToAllowed() {
        assertThat(config(Map.of()).cacheAllowed()).isTrue();
        assertThat(config(Map.of(SourceConfig.FIELD_CACHE, false)).cacheAllowed()).isFalse();
    }

    private static SourceConfig config(Map<String, Object> extras) {
        return new SourceConfig(
                "alpha", "_vance/config/mounts/alpha.yaml", "ode",
                "https://alpha.test/", null, true, extras);
    }
}
