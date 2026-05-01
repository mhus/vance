package de.mhus.vance.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AesEncryptionServiceTest {

    private static final String MASTER = "vance-master-test-passphrase-1";

    @Test
    void encryptDecrypt_roundTrips() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        String plain = "hunter2";
        String cipher = svc.encrypt(plain);

        assertThat(cipher).isNotNull().isNotEqualTo(plain);
        assertThat(svc.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    void encrypt_producesFreshIv_forIdenticalPlaintext() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        // GCM requires a unique IV per encryption — repeated encrypts of the
        // same plaintext must yield distinct ciphertexts.
        String a = svc.encrypt("same-secret");
        String b = svc.encrypt("same-secret");

        assertThat(a).isNotEqualTo(b);
        assertThat(svc.decrypt(a)).isEqualTo("same-secret");
        assertThat(svc.decrypt(b)).isEqualTo("same-secret");
    }

    @Test
    void encrypt_passesThroughNull() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        assertThat(svc.encrypt(null)).isNull();
    }

    @Test
    void decrypt_passesThroughNullOrBlank() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        assertThat(svc.decrypt(null)).isNull();
        assertThat(svc.decrypt("")).isNull();
        assertThat(svc.decrypt("   ")).isNull();
    }

    @Test
    void encrypt_emptyString_isPreserved() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        // Empty string is not the same as null — encrypting "" must
        // round-trip back to "".
        String cipher = svc.encrypt("");
        assertThat(cipher).isNotNull();
        assertThat(svc.decrypt(cipher)).isEqualTo("");
    }

    @Test
    void decrypt_withWrongMasterPassword_throws() {
        AesEncryptionService a = new AesEncryptionService("password-A");
        AesEncryptionService b = new AesEncryptionService("password-B");

        String cipher = a.encrypt("secret");

        assertThatThrownBy(() -> b.decrypt(cipher))
                .isInstanceOf(AesEncryptionService.EncryptionException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);
        String cipher = svc.encrypt("secret");

        // Flip a bit in the ciphertext — GCM auth-tag must catch this.
        byte[] bytes = java.util.Base64.getDecoder().decode(cipher);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> svc.decrypt(tampered))
                .isInstanceOf(AesEncryptionService.EncryptionException.class);
    }

    @Test
    void constructor_rejectsBlankPassword() {
        assertThatThrownBy(() -> new AesEncryptionService(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesEncryptionService("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptWith_decryptWith_areInterchangeable_acrossInstances() {
        // Vault-style: kit subsystem encrypts with a user passphrase
        // independent of the server master key. Wire-format must be
        // compatible with the instance methods on a service initialised
        // with the same passphrase.
        String vault = "user-vault-pass";
        String cipher = AesEncryptionService.encryptWith("kit-secret", vault);

        AesEncryptionService svc = new AesEncryptionService(vault);
        assertThat(svc.decrypt(cipher)).isEqualTo("kit-secret");

        // And vice-versa.
        String cipher2 = svc.encrypt("kit-secret-2");
        assertThat(AesEncryptionService.decryptWith(cipher2, vault))
                .isEqualTo("kit-secret-2");
    }

    @Test
    void encryptWith_rejectsEmptyPassword() {
        assertThatThrownBy(() -> AesEncryptionService.encryptWith("x", ""))
                .isInstanceOf(AesEncryptionService.EncryptionException.class);
        assertThatThrownBy(() -> AesEncryptionService.encryptWith("x", null))
                .isInstanceOf(AesEncryptionService.EncryptionException.class);
    }

    @Test
    void decryptWith_rejectsEmptyPassword() {
        assertThatThrownBy(() -> AesEncryptionService.decryptWith("xx", ""))
                .isInstanceOf(AesEncryptionService.EncryptionException.class);
        assertThatThrownBy(() -> AesEncryptionService.decryptWith("xx", null))
                .isInstanceOf(AesEncryptionService.EncryptionException.class);
    }
}
