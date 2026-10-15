package de.mhus.vance.brain.kit;

import java.util.Map;

/**
 * The public keys the default library ({@link KitSourceRegistry#DEFAULT_LIBRARY_URL})
 * signs its deliveries with, built into the brain.
 *
 * <p>Built in rather than fetched from the library: a key the library
 * hands out over HTTPS proves no more than control of its domain, and
 * whoever had that could hand out their own key along with their own
 * kits. A key that ships with the release is trusted because the release
 * is.
 *
 * <p><b>Rotation</b> (spec {@code kits.md} §12.1): a release carries the
 * current <em>and</em> the next key before the delivery side switches, so
 * brains that are a release or two behind keep installing; a later
 * release drops the retired one. A compromised key cannot wait for that —
 * a tenant replaces the ring in {@code kit-sources.yaml} right away,
 * which wins over this one — for as long as that entry stands, so it
 * also hides keys later releases add here (limits of that path: §12.1).
 *
 * <p>The id is shared with the delivery side's default
 * {@code vance.delivery.signing-key-id}; {@code KitLibraryKeysTest} pins it.
 */
public final class KitLibraryKeys {

    private KitLibraryKeys() {}

    private static final Map<String, String> KEYS = Map.of("vancetope-library-2026", """
                    -----BEGIN PUBLIC KEY-----
                    MCowBQYDK2VwAyEA6PGT78YUMO9s7N9d/A0jZ/Gtr0w4Y8kNCRKlO0VaGAg=
                    -----END PUBLIC KEY-----
                    """);

    /** Key id → PEM-encoded public key. */
    public static Map<String, String> bundled() {
        return KEYS;
    }
}
