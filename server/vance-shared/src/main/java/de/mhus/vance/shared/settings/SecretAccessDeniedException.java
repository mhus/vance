package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;

/**
 * Thrown when an authored {@code {{secret:…}}} reference tries to resolve a
 * setting that is not reference-readable — i.e. a
 * {@link SettingType#PASSWORD}-typed one.
 *
 * <p>Deliberately an exception rather than a {@code null} return: the
 * reference-resolution path treats {@code null} as "unresolved" and substitutes
 * the empty string, which would make a denied secret indistinguishable from a
 * missing one and surface downstream as an opaque 401. The named failure tells
 * the operator exactly what to change.
 *
 * <p>It propagates through {@code SettingsSecretResolver} uncaught, the same way
 * {@code OAuthExpiredException} does — both carry a "the human has to fix
 * something" signal that must not be swallowed by the fail-closed-to-empty rule
 * for genuinely absent secrets.
 *
 * @see SettingType#referenceReadable()
 */
public class SecretAccessDeniedException extends RuntimeException {

    private final String key;

    public SecretAccessDeniedException(String key, SettingType type) {
        super("setting '" + key + "' is " + type + "-typed and cannot be resolved through a "
                + "secret reference: PASSWORD settings can neither be read nor overwritten by "
                + "an agent-reachable path. Use type HIDDEN if this value is meant for tool, "
                + "compose or script use.");
        this.key = key;
    }

    /** The setting key that was refused — for callers rendering a hint. */
    public String getKey() {
        return key;
    }
}
