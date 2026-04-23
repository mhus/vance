package de.mhus.vance.shared.keystore;

/**
 * Which half of a crypto key is stored in a {@code KeyDocument}.
 */
public enum KeyKind {
    /** Asymmetric private key — kept on the signing side only. */
    PRIVATE,
    /** Asymmetric public key — distributed for verification. */
    PUBLIC,
    /** Symmetric secret — signing and verification share the same bytes. */
    SECRET
}
