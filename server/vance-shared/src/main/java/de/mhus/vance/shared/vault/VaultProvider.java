package de.mhus.vance.shared.vault;

import org.jspecify.annotations.Nullable;

/**
 * Adapter to one external secret manager. v1 has one implementation,
 * {@code InfisicalVaultProvider} (in {@code vance-brain}). Other managers
 * (HashiCorp Vault / OpenBao, Bitwarden Secrets Manager, …) can be added as
 * new {@code @Component} beans — or dedicated addon modules — without touching
 * {@link VaultService}, which selects the right bean at runtime by
 * {@link #type()} against the {@code vault.type} setting.
 *
 * <p>Implementations are stateless: they receive the resolved
 * {@link VaultBinding} on every call rather than holding connection state, so
 * the same bean serves every tenant / project / user scope. Any short-lived
 * connection state a provider needs (e.g. an Infisical access token obtained
 * via Universal Auth) is that provider's own concern and must be cached inside
 * it, keyed by the binding.
 *
 * <p>Transport / auth failures throw {@link VaultException}. A genuinely absent
 * secret returns {@code null} from {@link #readSecret} — it is not an error.
 */
public interface VaultProvider {

    /** Identifier matched against the {@code vault.type} setting. */
    String type();

    /**
     * Read the value of {@code key} from the vault described by {@code binding}.
     *
     * @return the secret value, or {@code null} if the vault has no such key
     * @throws VaultException on transport / auth failure
     */
    @Nullable String readSecret(VaultBinding binding, String key);

    /**
     * Create or update {@code key} = {@code value} in the vault.
     *
     * <p>Default throws {@link UnsupportedOperationException} — a provider only
     * gains write support by overriding this. Even where implemented, the hard
     * backstop is the credential's own scope: a read-only machine identity
     * makes the underlying call fail regardless.
     */
    default void writeSecret(VaultBinding binding, String key, String value) {
        throw new UnsupportedOperationException(
                "Vault provider '" + type() + "' does not support writing secrets");
    }
}
