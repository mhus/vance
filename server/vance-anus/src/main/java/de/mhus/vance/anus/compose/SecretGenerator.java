package de.mhus.vance.anus.compose;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Random-secret + BCrypt-hash helper for the docker-compose scaffolder.
 *
 * <p>Standalone (no Spring): the compose wizard runs before any context boots,
 * so it can't reach the {@code PasswordService} bean. BCrypt hashing goes
 * straight through {@link BCryptPasswordEncoder} (same algorithm/defaults the
 * live {@code PasswordService} uses, so a hash generated here verifies against
 * the running Brain/Anus login).
 */
final class SecretGenerator {

    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * URL-safe (no padding) random token with {@code bytes} of entropy. Used for
     * generated passwords and the internal token — the character set is safe to
     * drop into a {@code .env} value unquoted.
     */
    String token(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** BCrypt hash of a plaintext login password, for {@code VANCE_ANUS_PASSWORD_HASH}. */
    String bcrypt(String plaintext) {
        return encoder.encode(plaintext);
    }
}
