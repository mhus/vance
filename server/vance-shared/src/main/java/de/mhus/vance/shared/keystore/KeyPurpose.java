package de.mhus.vance.shared.keystore;

/**
 * Canonical {@code purpose} values used with {@link KeyService}.
 *
 * {@code purpose} scopes a key pair within a tenant. A tenant can hold unrelated
 * keys for different purposes without collisions.
 */
public final class KeyPurpose {

    /** Signing key for Vance JWT tokens. */
    public static final String JWT_SIGNING = "jwt-signing";

    private KeyPurpose() {
    }
}
