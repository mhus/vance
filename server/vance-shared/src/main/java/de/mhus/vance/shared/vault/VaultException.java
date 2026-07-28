package de.mhus.vance.shared.vault;

/**
 * Raised when a vault secret cannot be resolved for a structural reason —
 * no vault bound at the requested scope, no provider registered for the
 * configured {@code vault.type}, a missing required binding setting, or a
 * provider-side transport/auth failure.
 *
 * <p>A genuinely <em>absent</em> secret (the vault is reachable but has no
 * such key) is <b>not</b> an exception — {@link VaultService#readSecret}
 * returns {@code null} in that case. Callers that need fail-closed behaviour
 * (e.g. the {@code {{secret:vault:...}}} resolver) translate both {@code null}
 * and this exception into an empty substitution plus a warning, so a failed
 * lookup surfaces as a downstream 401 rather than a leaked or half-built call.
 */
public class VaultException extends RuntimeException {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
