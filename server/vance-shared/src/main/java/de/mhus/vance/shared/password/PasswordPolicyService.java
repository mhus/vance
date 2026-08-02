package de.mhus.vance.shared.password;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Enforces the tenant-wide minimum password policy for account passwords.
 *
 * <p>The policy is a global, hard-coded baseline (no per-tenant setting) and
 * follows current NIST guidance: length beats forced character-class rules.
 * The rules are:
 *
 * <ul>
 *   <li>at least {@link #MIN_LENGTH} characters,</li>
 *   <li>at most {@link #MAX_BYTES} bytes when UTF-8 encoded — BCrypt silently
 *       truncates beyond 72 bytes, so anything longer is rejected rather than
 *       quietly weakened,</li>
 *   <li>not on the bundled list of common / breached passwords
 *       (case-insensitive).</li>
 * </ul>
 *
 * <p>No character-class requirements are imposed on purpose. This service is
 * the single choke point for password quality; every set-password path
 * (admin REST, self-service profile, Anus shell, setup wizard) calls
 * {@link #validate(String)} before hashing.
 */
@Service
@Slf4j
public class PasswordPolicyService {

    /** Minimum number of characters a password must have. */
    public static final int MIN_LENGTH = 10;

    /**
     * Hard upper bound in UTF-8 bytes. BCrypt only considers the first 72
     * bytes; a longer password would be silently truncated, so we reject it.
     */
    public static final int MAX_BYTES = 72;

    private static final String COMMON_PASSWORDS_RESOURCE =
            "/de/mhus/vance/shared/password/common-passwords.txt";

    /** Lower-cased common / breached passwords, loaded once at startup. */
    private final Set<String> commonPasswords;

    public PasswordPolicyService() {
        this.commonPasswords = loadCommonPasswords();
        log.info("Password policy active: minLength={} maxBytes={} blocklistSize={}",
                MIN_LENGTH, MAX_BYTES, commonPasswords.size());
    }

    /**
     * Validates {@code plaintext} against the policy. Returns normally when it
     * passes; otherwise throws with a user-facing message describing the one
     * rule that failed.
     *
     * @throws PasswordPolicyException if the password violates a rule
     */
    public void validate(String plaintext) {
        if (plaintext.length() < MIN_LENGTH) {
            throw new PasswordPolicyException(
                    "Password must be at least " + MIN_LENGTH + " characters long.");
        }
        int bytes = plaintext.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new PasswordPolicyException(
                    "Password is too long — must be at most " + MAX_BYTES
                            + " bytes when UTF-8 encoded.");
        }
        if (commonPasswords.contains(plaintext.toLowerCase())) {
            throw new PasswordPolicyException(
                    "Password is too common — choose a less predictable one.");
        }
    }

    private static Set<String> loadCommonPasswords() {
        Set<String> set = new HashSet<>();
        try (InputStream in = PasswordPolicyService.class
                .getResourceAsStream(COMMON_PASSWORDS_RESOURCE)) {
            if (in == null) {
                log.warn("Common-password blocklist resource not found at {} — blocklist disabled",
                        COMMON_PASSWORDS_RESOURCE);
                return set;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.toLowerCase());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load common-password blocklist — blocklist disabled: {}",
                    e.getMessage());
        }
        return set;
    }
}
