package de.mhus.vance.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for reversibly-stored secrets.
 *
 * <p><b>Wire format (v1):</b> {@code Base64(0x01 || salt[16] || iv[12] ||
 * ciphertext+tag)}. The AES key is derived from the master/vault password
 * and the per-blob salt via PBKDF2-HMAC-SHA256 ({@value #PBKDF2_ITERATIONS}
 * iterations) — a salted, work-factored KDF, so identical passwords no
 * longer yield identical keys and offline brute-forcing is expensive
 * (code-review F4).
 *
 * <p><b>Backward compatibility:</b> blobs written by the previous format
 * ({@code Base64(iv[12] || ciphertext+tag)}, key = single-round
 * {@code SHA-256(password)}) are still decryptable — decryption detects the
 * version byte and falls back to the legacy scheme. Nothing new is ever
 * written in the legacy format, so at-rest secrets migrate to the strong
 * KDF the next time they are re-saved.
 *
 * <p>Rotating the master password invalidates all existing ciphertexts —
 * plan a migration before changing it.
 */
@Service
@Slf4j
public class AesEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    /** v1 envelope constants. */
    private static final byte VERSION_1 = 0x01;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    /** OWASP-2023 floor for PBKDF2-HMAC-SHA256. */
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int V1_MIN_BYTES =
            1 + SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8);

    /**
     * Master key must not boot on the publicly-known default that older
     * configs shipped — that would encrypt every secret under a key anyone
     * can reproduce.
     */
    private static final String KNOWN_INSECURE_DEFAULT = "changeit";

    private final String password;
    /** Legacy SHA-256 key, kept only to decrypt pre-v1 blobs. */
    private final SecretKey legacyKey;
    private final SecureRandom secureRandom;
    /**
     * Amortizes the (deliberately slow) PBKDF2 derivation across repeated
     * decrypts of the same blob — provider API keys, for instance, are
     * decrypted on the LLM call path. Keyed by the per-blob salt; bounded
     * so it cannot grow without limit.
     */
    private final Map<String, SecretKey> v1KeyCache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SecretKey> eldest) {
                    return size() > 256;
                }
            });

    public AesEncryptionService(@Value("${vance.encryption.password}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "vance.encryption.password must be configured");
        }
        if (KNOWN_INSECURE_DEFAULT.equals(password)) {
            throw new IllegalStateException(
                    "vance.encryption.password must not be the known default '"
                            + KNOWN_INSECURE_DEFAULT + "' — set VANCE_ENCRYPTION_PASSWORD"
                            + " to a strong, private value");
        }
        this.password = password;
        this.legacyKey = deriveLegacyKey(password);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypts {@code plaintext} and returns a Base64-encoded self-contained
     * v1 blob. Returns {@code null} if {@code plaintext} is {@code null} so
     * callers can pipe through cleanly.
     */
    public @Nullable String encrypt(@Nullable String plaintext) {
        if (plaintext == null) return null;
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        return encryptV1(plaintext, cachedV1Key(salt), salt, secureRandom);
    }

    /**
     * Decrypts a blob produced by {@link #encrypt(String)} (or a legacy
     * blob). Returns {@code null} for {@code null} / blank input.
     */
    public @Nullable String decrypt(@Nullable String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("AES decryption failed", e);
        }
        if (isV1(decoded)) {
            try {
                return decryptV1(decoded, saltKey -> cachedV1Key(saltKey));
            } catch (EncryptionException e) {
                // Fall through: a legacy blob may coincidentally start with
                // the version byte. Only rethrow if legacy also fails.
            }
        }
        return decryptLegacy(decoded, legacyKey);
    }

    /**
     * Encrypts {@code plaintext} with a key derived from {@code password}.
     * Used by the kit subsystem to re-encrypt PASSWORD-settings with a
     * user-supplied vault passphrase for export. Same wire-format as
     * {@link #encrypt(String)} so blobs are interchangeable across keys.
     */
    public static @Nullable String encryptWith(
            @Nullable String plaintext, String password) {
        if (password == null || password.isEmpty()) {
            throw new EncryptionException("password must not be empty", null);
        }
        if (plaintext == null) return null;
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        rng.nextBytes(salt);
        return encryptV1(plaintext, deriveKeyV1(password, salt), salt, rng);
    }

    /**
     * Decrypts a blob with a key derived from {@code password}. Counterpart
     * to {@link #encryptWith(String, String)} for kit imports; also reads
     * legacy blobs.
     */
    public static @Nullable String decryptWith(
            @Nullable String ciphertext, String password) {
        if (password == null || password.isEmpty()) {
            throw new EncryptionException("password must not be empty", null);
        }
        if (ciphertext == null || ciphertext.isBlank()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("AES decryption failed", e);
        }
        if (isV1(decoded)) {
            try {
                return decryptV1(decoded, salt -> deriveKeyV1(password, salt));
            } catch (EncryptionException e) {
                // fall through to legacy
            }
        }
        return decryptLegacy(decoded, deriveLegacyKey(password));
    }

    // ── v1 codec ────────────────────────────────────────────────────

    private static boolean isV1(byte[] decoded) {
        return decoded.length >= V1_MIN_BYTES && decoded[0] == VERSION_1;
    }

    private static String encryptV1(
            String plaintext, SecretKey key, byte[] salt, SecureRandom rng) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(
                    1 + salt.length + iv.length + encrypted.length);
            buffer.put(VERSION_1);
            buffer.put(salt);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new EncryptionException("AES encryption failed", e);
        }
    }

    private interface KeyForSalt {
        SecretKey apply(byte[] salt);
    }

    private static String decryptV1(byte[] decoded, KeyForSalt keyForSalt) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            buffer.get(); // version
            byte[] salt = new byte[SALT_LENGTH_BYTES];
            buffer.get(salt);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keyForSalt.apply(salt),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("AES decryption failed", e);
        }
    }

    private static String decryptLegacy(byte[] decoded, SecretKey key) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("AES decryption failed", e);
        }
    }

    // ── key derivation ──────────────────────────────────────────────

    private SecretKey cachedV1Key(byte[] salt) {
        String cacheKey = Base64.getEncoder().encodeToString(salt);
        return v1KeyCache.computeIfAbsent(cacheKey, k -> deriveKeyV1(password, salt));
    }

    private static SecretKey deriveKeyV1(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            byte[] key = SecretKeyFactory.getInstance(KDF_ALGORITHM)
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new EncryptionException("Failed to derive AES key from password", e);
        }
    }

    private static SecretKey deriveLegacyKey(String password) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new EncryptionException("Failed to derive AES key from password", e);
        }
    }

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
