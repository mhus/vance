package de.mhus.vance.age;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AgeStreamsTest {

    private final AgeKeys.AgeKeyPair keys = AgeKeys.generateKeyPair();

    private byte[] encrypt(byte[] plain, List<String> recipients) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = AgeStreams.encrypt(sink, recipients)) {
            out.write(plain);
        }
        return sink.toByteArray();
    }

    private byte[] decrypt(byte[] cipher, String identity) throws IOException {
        try (InputStream in = AgeStreams.decrypt(new ByteArrayInputStream(cipher), List.of(identity))) {
            return in.readAllBytes();
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    void roundTrip_smallAndEmptyAndMultiChunk() throws IOException {
        for (int size : new int[] {0, 1, 65_535, 65_536, 65_537, 300_000}) {
            byte[] plain = random(size);

            byte[] cipher = encrypt(plain, List.of(keys.recipient()));

            assertThat(decrypt(cipher, keys.identity())).as("size %d", size).isEqualTo(plain);
        }
    }

    @Test
    void output_isNotThePlaintext_andStartsLikeAgeV1() throws IOException {
        byte[] cipher = encrypt("secret secret secret".getBytes(StandardCharsets.UTF_8), List.of(keys.recipient()));

        assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).startsWith("age-encryption.org/v1\n");
        assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).doesNotContain("secret");
    }

    @Test
    void everyRecipientCanDecrypt_andOthersCannot() throws IOException {
        AgeKeys.AgeKeyPair second = AgeKeys.generateKeyPair();
        AgeKeys.AgeKeyPair stranger = AgeKeys.generateKeyPair();
        byte[] plain = random(1000);

        byte[] cipher = encrypt(plain, List.of(keys.recipient(), second.recipient()));

        assertThat(decrypt(cipher, keys.identity())).isEqualTo(plain);
        assertThat(decrypt(cipher, second.identity())).isEqualTo(plain);
        assertThatThrownBy(() -> decrypt(cipher, stranger.identity())).isInstanceOf(AgeNoKeyMatchException.class);
    }

    @Test
    void truncatedStream_failsWhileReading_neverSilently() throws IOException {
        byte[] cipher = encrypt(random(200_000), List.of(keys.recipient()));
        byte[] cut = Arrays.copyOf(cipher, cipher.length - 40);

        assertThatThrownBy(() -> decrypt(cut, keys.identity())).isInstanceOf(IOException.class);
    }

    @Test
    void tamperedPayload_failsAuthentication() throws IOException {
        byte[] cipher = encrypt(random(10_000), List.of(keys.recipient()));
        cipher[cipher.length - 20] ^= 0x01;

        assertThatThrownBy(() -> decrypt(cipher, keys.identity())).isInstanceOf(IOException.class);
    }

    @Test
    void malformedKeysAndEmptyLists_areRefusedClearly() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        assertThatThrownBy(() -> AgeStreams.encrypt(sink, List.of("not-a-key")))
                .isInstanceOf(AgeCryptoException.class)
                .hasMessageContaining("Not a well-formed age recipient");
        assertThatThrownBy(() -> AgeStreams.encrypt(sink, List.of())).isInstanceOf(AgeCryptoException.class);
        assertThatThrownBy(() -> AgeStreams.decrypt(new ByteArrayInputStream(new byte[0]), List.of("nope")))
                .isInstanceOf(AgeCryptoException.class)
                .hasMessageContaining("Not a well-formed age identity");
        assertThatThrownBy(() -> AgeStreams.decrypt(new ByteArrayInputStream(new byte[0]), List.of()))
                .isInstanceOf(AgeNoKeyMatchException.class);
    }

    @Test
    void readsTheBinaryFormOfTheReferenceCliFixture() throws IOException {
        // The Go age CLI's armored file is base64 of the binary file: strip the armor lines.
        String armored = resource("age/hello-x25519.age");
        String base64 = armored.lines().filter(l -> !l.startsWith("-----")).reduce("", String::concat);
        byte[] binary = Base64.getMimeDecoder().decode(base64);
        String identity = AgeKeys.extractIdentity(resource("age/test-identity.txt"));

        assertThat(new String(decrypt(binary, identity), StandardCharsets.UTF_8))
                .isEqualTo("Hello, age fixture!\n");
    }

    @Test
    void interoperatesWithTheArmoredFacade_inBothDirections() throws IOException {
        // Facade → stream: the armored form of a facade encryption decodes to a stream-readable file.
        String armored = AgeCipher.encryptArmored("via facade", List.of(keys.recipient()));
        String base64 = armored.lines().filter(l -> !l.startsWith("-----")).reduce("", String::concat);
        assertThat(new String(decrypt(Base64.getMimeDecoder().decode(base64), keys.identity()), StandardCharsets.UTF_8))
                .isEqualTo("via facade");

        // Stream → facade: wrap the stream output in armor by hand and let the facade read it.
        byte[] cipher = encrypt("via stream".getBytes(StandardCharsets.UTF_8), List.of(keys.recipient()));
        String wrapped = "-----BEGIN AGE ENCRYPTED FILE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(cipher)
                + "\n-----END AGE ENCRYPTED FILE-----\n";
        assertThat(AgeCipher.decryptArmored(wrapped, new AgeSecrets(List.of(keys.identity()), List.of())))
                .isEqualTo("via stream");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = AgeStreamsTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
