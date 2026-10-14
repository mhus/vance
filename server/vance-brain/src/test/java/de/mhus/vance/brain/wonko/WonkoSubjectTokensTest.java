package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WonkoSubjectTokensTest {

    @Test
    void referenceTokens_findsPathsIdsAndFileNamesInProse() {
        assertThat(WonkoEngine.referenceTokens(
                        "schau dir mal das Dokument `specs/payment-flow.md` an, und 6acfab4fdfd4401e90ed962e."))
                .containsExactly("specs/payment-flow.md", "6acfab4fdfd4401e90ed962e");
        assertThat(WonkoEngine.referenceTokens("check notes.md and (docs/a/b)"))
                .containsExactly("notes.md", "docs/a/b");
    }

    @Test
    void referenceTokens_plainProse_yieldsNothing() {
        assertThat(WonkoEngine.referenceTokens("Bitte prüf das mal, ok? Danke."))
                .isEmpty();
    }
}
