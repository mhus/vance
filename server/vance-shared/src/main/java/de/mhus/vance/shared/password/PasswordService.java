package de.mhus.vance.shared.password;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing / verification for the Vance login flow.
 *
 * <p>BCrypt is the chosen algorithm (good defaults, well understood, salt
 * baked into the hash string). Swapping to a different algorithm later only
 * needs a change here — callers stay on {@link #hash(String)} /
 * {@link #verify(String, String)}.
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Returns a salted hash suitable for storage. Never returns the input. */
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    /** Constant-time-ish comparison of {@code plaintext} against a stored hash. */
    public boolean verify(String plaintext, String hash) {
        return encoder.matches(plaintext, hash);
    }
}
