package de.mhus.vance.shared.vault;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A resolved vault connection, assembled by {@link VaultService} from the
 * {@code vault.*} settings of one cascade layer and handed to the matching
 * {@link VaultProvider} per call. Providers are stateless — they receive the
 * binding on every {@code readSecret} / {@code writeSecret} rather than being
 * configured once, so a scope change (user vs. project vault) needs no
 * re-wiring.
 *
 * <p>{@link #type} selects the provider ({@code vault.type}); {@link #baseUrl}
 * is the universal endpoint ({@code vault.baseUrl}). Everything provider-specific
 * and non-secret lives in {@link #config} (e.g. Infisical {@code project} /
 * {@code environment} / {@code path} / {@code clientId}), populated from the
 * remaining {@code vault.*} keys. The single credential — decrypted from the
 * {@code vault.clientSecret} PASSWORD setting — is {@link #secret}; it is
 * {@code @Nullable} because a read-only public vault or an env-provided token
 * may legitimately carry none.
 *
 * @param type    provider discriminator; matched against {@link VaultProvider#type()}
 * @param baseUrl provider endpoint base URL
 * @param config  non-secret provider parameters, keyed by their {@code vault.<key>} suffix
 * @param secret  decrypted credential (Infisical client-secret / token), or {@code null}
 */
public record VaultBinding(
        String type,
        String baseUrl,
        Map<String, String> config,
        @Nullable String secret) {

    public VaultBinding {
        config = Map.copyOf(config);
    }
}
