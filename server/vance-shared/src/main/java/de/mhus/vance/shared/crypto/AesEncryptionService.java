package de.mhus.vance.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for reversibly-stored secrets.
 *
 * <p>Key is derived from the {@code vance.encryption.password} property via
 * SHA-256. Each ciphertext carries its own 96-bit IV; the payload is
 * {@code Base64(iv || ciphertext+tag)}.
 *
 * <p>Rotating the master password invalidates all existing ciphertexts — plan
 * a migration before changing it.
 */
@Service
@Slf4j
public class AesEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    public AesEncryptionService(@Value("${vance.encryption.password}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "vance.encryption.password must be configured");
        }
        this.secretKey = deriveKey(password);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypts {@code plaintext} and returns a Base64-encoded self-contained
     * blob (IV prepended). Returns {@code null} if {@code plaintext} is
     * {@code null} so callers can pipe through cleanly.
     */
    public @Nullable String encrypt(@Nullable String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new EncryptionException("AES encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64 blob produced by {@link #encrypt(String)}. Returns
     * {@code null} for {@code null} / blank input.
     */
    public @Nullable String decrypt(@Nullable String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("AES decryption failed", e);
        }
    }

    private static SecretKey deriveKey(String password) {
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
