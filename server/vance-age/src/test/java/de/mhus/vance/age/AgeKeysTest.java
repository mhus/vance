package de.mhus.vance.age;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Key shape handling — the strict v1 forms the facade and the UI rely on. */
class AgeKeysTest {

    // The committed throw-away keygen pair (regeneration commands in
    // client/packages/age/src/fixtures/README.md); read rather than
    // hard-coded so regenerating the fixtures keeps this test green.
    private final String keygenFile = read("age/test-identity.txt");

    @Test
    void recognisesIdentityAndRecipientShapes() {
        String identity = AgeKeys.extractIdentity(keygenFile);
        String recipient = AgeKeys.extractRecipient(keygenFile);

        assertThat(identity).isNotNull();
        assertThat(recipient).startsWith("age1");
        assertThat(AgeKeys.isIdentity(identity)).isTrue();
        assertThat(AgeKeys.isRecipient(recipient)).isTrue();

        // Cross-wise: a recipient is not an identity and vice versa.
        assertThat(AgeKeys.isIdentity(recipient)).isFalse();
        assertThat(AgeKeys.isRecipient(identity)).isFalse();
        // Post-quantum variants are deliberately out of v1 scope.
        assertThat(AgeKeys.isIdentity("AGE-SECRET-KEY-PQ1" + identity.substring(16))).isFalse();
        assertThat(AgeKeys.isIdentity("AGE-SECRET-KEY-1NOT-A-REAL-KEY")).isFalse();
        assertThat(AgeKeys.isIdentity("")).isFalse();
        assertThat(AgeKeys.isRecipient("age1notarealkey")).isFalse();
        assertThat(AgeKeys.isRecipient(null)).isFalse();
    }

    @Test
    void trimsBeforeChecking() {
        String identity = AgeKeys.extractIdentity(keygenFile);
        assertThat(AgeKeys.isIdentity("  " + identity + "\n")).isTrue();
    }

    @Test
    void extractsFromWholeKeygenFile() {
        // The exact shape a user pastes after running age-keygen —
        // comment lines above the secret, CRLF tolerated.
        assertThat(AgeKeys.extractIdentity(keygenFile.replace("\n", "\r\n")))
                .isEqualTo(AgeKeys.extractIdentity(keygenFile));
        assertThat(AgeKeys.extractIdentity("# nothing here\nage1 nope")).isNull();
        assertThat(AgeKeys.extractRecipient("AGE-SECRET-KEY-1WRONG")).isNull();
    }

    @Test
    void extractsEveryIdentityFromMultiKeyFiles() {
        // age -i reads every AGE-SECRET-KEY-1 line — so does foot's
        // identity-file prompt.
        String first = AgeKeys.extractIdentity(keygenFile);
        String second = AgeKeys.generateKeyPair().identity();
        String file = "# comment\n" + first + "\n" + second + "\n" + first + "\n";

        assertThat(AgeKeys.extractIdentities(file)).containsExactly(first, second);
        assertThat(AgeKeys.extractIdentities("# nothing")).isEmpty();
        assertThat(AgeKeys.extractIdentities(null)).isEmpty();
    }

    @Test
    void generatesWorkingPairs() {
        AgeKeys.AgeKeyPair pair = AgeKeys.generateKeyPair();

        assertThat(AgeKeys.isIdentity(pair.identity())).isTrue();
        assertThat(AgeKeys.isRecipient(pair.recipient())).isTrue();

        // The pair actually works end to end.
        String armored = AgeCipher.encryptArmored("generated", List.of(pair.recipient()));
        assertThat(AgeCipher.decryptArmored(armored,
                new AgeSecrets(List.of(pair.identity()), List.of())))
                .isEqualTo("generated");
    }

    private static String read(String resource) {
        try (InputStream in = AgeKeysTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + resource, e);
        }
    }
}
