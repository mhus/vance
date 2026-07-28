package de.mhus.vance.brain.vault;

import de.mhus.vance.shared.vault.VaultBinding;
import de.mhus.vance.shared.vault.VaultProvider;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link VaultProvider} for Infisical (v1's only implementation). Thin adapter
 * over {@link InfisicalClient} — selection by {@code vault.type == "infisical"}
 * happens in {@code VaultService}; all transport lives in the client.
 *
 * <p>Write is supported at the provider level so secret generation/writing can
 * be wired up later; the hard backstop stays the machine identity's own scope —
 * a read-only Infisical token makes {@link #writeSecret} fail regardless.
 */
@Component
@RequiredArgsConstructor
public class InfisicalVaultProvider implements VaultProvider {

    static final String TYPE = "infisical";

    private final InfisicalClient client;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public @Nullable String readSecret(VaultBinding binding, String key) {
        return client.readSecret(binding, key);
    }

    @Override
    public void writeSecret(VaultBinding binding, String key, String value) {
        client.writeSecret(binding, key, value);
    }
}
