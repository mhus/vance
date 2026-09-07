package de.mhus.vance.age;

import java.util.List;

/**
 * Every secret a decryption may try on one document — all of them are
 * offered to the file, first match wins (mirrors {@code AgeSecrets} in
 * the TypeScript twin {@code @vance/age}).
 *
 * @param identities  X25519 private keys, {@code AGE-SECRET-KEY-1…}
 * @param passphrases symmetric secrets for scrypt-encrypted files
 */
public record AgeSecrets(List<String> identities, List<String> passphrases) {

    public static final AgeSecrets EMPTY = new AgeSecrets(List.of(), List.of());

    public AgeSecrets {
        identities = identities == null ? List.of() : List.copyOf(identities);
        passphrases = passphrases == null ? List.of() : List.copyOf(passphrases);
    }

    public boolean isEmpty() {
        return identities.isEmpty() && passphrases.isEmpty();
    }
}
