package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Pins the id the bundled library key is filed under.
 *
 * <p>The id is a contract with the delivery side: the library signs with
 * {@code vance.delivery.signing-key-id} (vance-ee {@code SigningKeys}), and
 * a brain only tries the key its ring holds under exactly that id. Renaming
 * one side without the other does not fail anywhere at build time — it
 * fails at the first install, as {@code UNKNOWN_KEY}. The twin of this test
 * lives in vance-ee and pins the default there.
 */
class KitLibraryKeysTest {

    /** Must equal the delivery side's default {@code vance.delivery.signing-key-id}. */
    static final String EXPECTED_DEFAULT_KEY_ID = "vancetope-library-2026";

    @Test
    void bundledRing_holdsTheLibraryKeyUnderTheDeliveryDefaultId() {
        assertThat(KitLibraryKeys.bundled()).containsKey(EXPECTED_DEFAULT_KEY_ID);
    }

    @Test
    void bundledKeys_areAllParseableEd25519Keys() {
        // A broken bundled key would only show at the first install, and then
        // as a failed signature rather than as the build mistake it is.
        KitLibraryKeys.bundled()
                .forEach((id, pem) -> assertThatCode(() -> KitSignature.parsePublicKey(pem))
                        .describedAs("bundled key '%s'", id)
                        .doesNotThrowAnyException());
    }
}
