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

    /**
     * A real BCrypt hash of a fixed decoy secret, computed once at startup.
     * {@link #verifyDecoy} matches against it so an authentication failure
     * that never reached a real hash (unknown / inactive / hash-less user)
     * still spends the same ~BCrypt CPU as a genuine wrong-password check.
     */
    private final String decoyHash = encoder.encode("vance-timing-decoy");

    /** Returns a salted hash suitable for storage. Never returns the input. */
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    /** Constant-time-ish comparison of {@code plaintext} against a stored hash. */
    public boolean verify(String plaintext, String hash) {
        return encoder.matches(plaintext, hash);
    }

    /**
     * Runs a full BCrypt verify against an internal decoy hash and discards
     * the result (always returns {@code false}). Call on every authentication
     * failure path that would otherwise short-circuit before {@link #verify}
     * (unknown user, inactive, no stored hash), so response timing can't
     * distinguish those from a valid user with a wrong password — closing the
     * user-enumeration timing side-channel.
     */
    public boolean verifyDecoy(String plaintext) {
        encoder.matches(plaintext == null ? "" : plaintext, decoyHash);
        return false;
    }
}
