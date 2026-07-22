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

    @Test
    void constructor_rejectsKnownInsecureDefault() {
        // Booting on the publicly-known default would encrypt every secret
        // under a key anyone can reproduce (code-review F4).
        assertThatThrownBy(() -> new AesEncryptionService("changeit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changeit");
    }

    @Test
    void encrypt_producesVersionedV1Blob() {
        AesEncryptionService svc = new AesEncryptionService(MASTER);

        byte[] decoded = java.util.Base64.getDecoder().decode(svc.encrypt("x"));

        // First byte is the v1 version marker; salt(16)+iv(12)+tag(16) follow.
        assertThat(decoded[0]).isEqualTo((byte) 0x01);
        assertThat(decoded.length).isGreaterThanOrEqualTo(1 + 16 + 12 + 16);
    }

    @Test
    void decrypt_readsLegacyBlob() throws Exception {
        // A blob written by the previous format — key = single-round
        // SHA-256(password), payload = Base64(iv || ct+tag) — must still
        // decrypt so at-rest secrets survive the KDF upgrade.
        String legacy = makeLegacyBlob(MASTER, "legacy-secret");

        AesEncryptionService svc = new AesEncryptionService(MASTER);
        assertThat(svc.decrypt(legacy)).isEqualTo("legacy-secret");
    }

    @Test
    void decrypt_readsLegacyBlob_viaVaultPassword() throws Exception {
        String legacy = makeLegacyBlob("vault-pw", "vault-secret");

        assertThat(AesEncryptionService.decryptWith(legacy, "vault-pw"))
                .isEqualTo("vault-secret");
    }

    private static String makeLegacyBlob(String password, String plaintext) throws Exception {
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "AES");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        return java.util.Base64.getEncoder().encodeToString(blob);
    }
}
