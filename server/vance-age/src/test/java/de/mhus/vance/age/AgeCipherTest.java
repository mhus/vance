package de.mhus.vance.age;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The facade against the reference-CLI fixtures — the same files the
 * TypeScript twin {@code @vance/age} decrypts (see
 * {@code client/packages/age/src/fixtures/README.md} for the generation
 * commands; the copies live here so both implementations are held to the
 * same bytes).
 */
class AgeCipherTest {

    private static final String PASSPHRASE_FIXTURE_TEXT =
            "Passphrase fixture for Vance — decrypt with the passphrase from the README.\n";

    private final String fixtureIdentity = fixtureIdentity();

    // ───────────────────── reference-CLI fixtures ─────────────────────

    @Test
    void decryptsGoAgeX25519File() {
        String armored = read("age/hello-x25519.age");

        String plaintext = AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(fixtureIdentity), List.of()));

        assertThat(plaintext).isEqualTo("Hello, age fixture!\n");
    }

    @Test
    void decryptsGoAgeMarkdownDocumentByteExactly() {
        String armored = read("age/markdown-x25519.age");
        String expected = read("age/markdown-source.md");

        String plaintext = AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(fixtureIdentity), List.of()));

        assertThat(plaintext).isEqualTo(expected);
    }

    @Test
    void decryptsGoAgePassphraseFile() {
        String armored = read("age/hello-passphrase.age");

        String plaintext = AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(), List.of("vance-fixture-passphrase-2026")));

        assertThat(plaintext).isEqualTo(PASSPHRASE_FIXTURE_TEXT);
    }

    // ───────────────────── round trips ─────────────────────

    @Test
    void roundTripsIdentityEncryption() {
        AgeKeys.AgeKeyPair pair = AgeKeys.generateKeyPair();

        String armored = AgeCipher.encryptArmored("# Geheim 🔐\n", List.of(pair.recipient()));

        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(pair.identity()), List.of())))
                .isEqualTo("# Geheim 🔐\n");
    }

    @Test
    void roundTripsEmptyDocument() {
        AgeKeys.AgeKeyPair pair = AgeKeys.generateKeyPair();

        String armored = AgeCipher.encryptArmored("", List.of(pair.recipient()));

        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(pair.identity()), List.of())))
                .isEmpty();
    }

    @Test
    void encryptsToSeveralRecipientsEachCanDecrypt() {
        AgeKeys.AgeKeyPair a = AgeKeys.generateKeyPair();
        AgeKeys.AgeKeyPair b = AgeKeys.generateKeyPair();

        String armored = AgeCipher.encryptArmored("shared", List.of(a.recipient(), b.recipient()));

        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(a.identity()), List.of()))).isEqualTo("shared");
        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(b.identity()), List.of()))).isEqualTo("shared");
    }

    @Test
    void triesEveryProvidedSecret() {
        AgeKeys.AgeKeyPair right = AgeKeys.generateKeyPair();
        AgeKeys.AgeKeyPair wrong = AgeKeys.generateKeyPair();

        String armored = AgeCipher.encryptArmored("mixed keys", List.of(right.recipient()));

        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(wrong.identity(), right.identity()), List.of())))
                .isEqualTo("mixed keys");
    }

    @Test
    void roundTripsPassphraseEncryption() {
        String armored = AgeCipher.encryptArmoredWithPassphrase("pw secret", "correct-horse");

        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(), List.of("correct-horse"))))
                .isEqualTo("pw secret");
    }

    // ───────────────────── error mapping ─────────────────────

    @Test
    void wrongIdentityIsNoKeyMatchNotBrokenArmor() {
        String armored = read("age/hello-x25519.age");
        AgeKeys.AgeKeyPair stranger = AgeKeys.generateKeyPair();

        assertThatThrownBy(() -> AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(stranger.identity()), List.of())))
                .isInstanceOf(AgeNoKeyMatchException.class);
    }

    @Test
    void wrongPassphraseIsNoKeyMatch() {
        String armored = read("age/hello-passphrase.age");

        assertThatThrownBy(() -> AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(), List.of("wrong-passphrase"))))
                .isInstanceOf(AgeNoKeyMatchException.class);
    }

    @Test
    void emptySecretsIsNoKeyMatch() {
        String armored = read("age/hello-x25519.age");

        assertThatThrownBy(() -> AgeCipher.decryptArmored(armored, AgeSecrets.EMPTY))
                .isInstanceOf(AgeNoKeyMatchException.class);
    }

    @Test
    void nonArmorTextIsAgeArmorException() {
        assertThatThrownBy(() -> AgeCipher.decryptArmored("# not age",
                new AgeSecrets(List.of(fixtureIdentity), List.of())))
                .isInstanceOf(AgeArmorException.class);
    }

    @Test
    void malformedRecipientIsRefusedBeforeEncrypting() {
        assertThatThrownBy(() -> AgeCipher.encryptArmored("x", List.of("age1notreal")))
                .isInstanceOf(AgeCryptoException.class)
                .hasMessageContaining("recipient");
        assertThatThrownBy(() -> AgeCipher.encryptArmored("x", List.of()))
                .isInstanceOf(AgeCryptoException.class);
    }

    // ───────────────────── helpers ─────────────────────

    private static String read(String resource) {
        try (InputStream in = AgeCipherTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + resource, e);
        }
    }

    private static String fixtureIdentity() {
        String keygenFile = read("age/test-identity.txt");
        String identity = AgeKeys.extractIdentity(keygenFile);
        if (identity == null) {
            throw new IllegalStateException("Fixture identity not found");
        }
        return identity;
    }
}
