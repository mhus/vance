package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The name a store suggests for a document it hands over.
 *
 * <p>It arrives in a remote party's header and leaves in one this brain
 * writes, which is the whole reason it is not passed through.
 */
class StoreClientFilenameTest {

    @Test
    void anOrdinaryName_survivesIntact() {
        assertThat(StoreClient.safeFilename("RE-2026-0042.pdf")).isEqualTo("RE-2026-0042.pdf");
    }

    @Test
    void aQuote_cannotEndTheValueItSitsIn() {
        // Content-Disposition: inline; filename="<here>" — a quote in the
        // middle would close it and let the rest be read as parameters.
        assertThat(StoreClient.safeFilename("a\";x=y.pdf")).isEqualTo("axy.pdf");
    }

    @Test
    void pathSeparatorsAndSpacesAreDropped() {
        assertThat(StoreClient.safeFilename(" ../../etc/passwd "))
                .isEqualTo("....etcpasswd");
    }

    @Test
    void aNameLongEnoughToBeAProblem_isCut() {
        assertThat(StoreClient.safeFilename("x".repeat(500))).hasSize(120);
    }

    @Test
    void aNameOfNothingUsable_comesBackEmptyForTheCallerToReplace() {
        // The caller falls back to the name it asked for. Better than a
        // header with an empty quoted value.
        assertThat(StoreClient.safeFilename("«»")).isEmpty();
    }
}
