package de.mhus.vance.brain.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    @Test
    void mask_replacesEachSecretValueWithStars() {
        String out = SecretMasker.mask(
                "token=s3cr3t-value and db=pa55word!", List.of("s3cr3t-value", "pa55word!"));
        assertThat(out).isEqualTo("token=*** and db=***");
    }

    @Test
    void mask_replacesAllOccurrences() {
        String out = SecretMasker.mask("abcd abcd abcd", List.of("abcd"));
        assertThat(out).isEqualTo("*** *** ***");
    }

    @Test
    void mask_skipsShortValues() {
        // A 3-char value is below MIN_LEN and must not blank out unrelated text.
        String out = SecretMasker.mask("the cat sat", List.of("cat"));
        assertThat(out).isEqualTo("the cat sat");
    }

    @Test
    void mask_noSecrets_returnsInputUnchanged() {
        assertThat(SecretMasker.mask("hello", List.of())).isEqualTo("hello");
    }

    @Test
    void mask_nullOrEmptyText_passesThrough() {
        assertThat(SecretMasker.mask(null, List.of("s3cr3t"))).isNull();
        assertThat(SecretMasker.mask("", List.of("s3cr3t"))).isEmpty();
    }
}
