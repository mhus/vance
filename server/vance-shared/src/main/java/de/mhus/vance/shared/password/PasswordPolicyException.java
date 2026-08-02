package de.mhus.vance.shared.password;

/**
 * Thrown by {@link PasswordPolicyService#validate(String)} when a proposed
 * plaintext password fails a policy rule. The {@link #getMessage() message}
 * is written to be shown to the end user verbatim, so it must not leak
 * anything beyond the failed rule.
 */
public class PasswordPolicyException extends RuntimeException {

    public PasswordPolicyException(String message) {
        super(message);
    }
}
