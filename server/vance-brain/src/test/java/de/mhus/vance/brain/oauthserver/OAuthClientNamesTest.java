package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OAuthClientNamesTest {

    @Test
    void sanitize_keepsAnOrdinaryName() {
        assertThat(OAuthClientNames.sanitize("Claude Code", 120)).isEqualTo("Claude Code");
        assertThat(OAuthClientNames.sanitize("Z\u00FCrich T\u00F6\u00F6l \u65E5\u672C", 120))
                .isEqualTo("Z\u00FCrich T\u00F6\u00F6l \u65E5\u672C");
    }

    @Test
    void sanitize_removesControlBidiAndZeroWidthCharacters() {
        String raw = "Van\u0000ce\u202Etope\u2066\u2069\u200B\u200D\uFEFF\u0007 CLI";

        assertThat(OAuthClientNames.sanitize(raw, 120)).isEqualTo("Vancetope CLI");
    }

    @Test
    void sanitize_collapsesWhitespaceAndLineBreaks() {
        assertThat(OAuthClientNames.sanitize("  My\n\tApp \u2028 Two\u00A0 ", 120))
                .isEqualTo("My App Two");
    }

    @Test
    void sanitize_nothingPrintableLeft_isNull() {
        assertThat(OAuthClientNames.sanitize("\u202E\u200B \n", 120)).isNull();
        assertThat(OAuthClientNames.sanitize("", 120)).isNull();
        assertThat(OAuthClientNames.sanitize(null, 120)).isNull();
    }

    @Test
    void sanitize_cutsAtACodePointBoundary() {
        String emoji = "\uD83D\uDE00"; // one code point, two chars

        assertThat(OAuthClientNames.sanitize("ab" + emoji + "cd", 3)).isEqualTo("ab" + emoji);
        assertThat(OAuthClientNames.sanitize("abc def", 4)).isEqualTo("abc");
        assertThat(OAuthClientNames.sanitize("x".repeat(500), 120)).hasSize(120);
    }

    @Test
    void sanitize_dropsLoneSurrogates() {
        assertThat(OAuthClientNames.sanitize("a\uD800b", 120)).isEqualTo("ab");
    }
}
