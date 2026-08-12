package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when an authored {@code {{secret:…}}} reference tries to resolve a
 * setting it may not read. Two independent reasons:
 *
 * <ul>
 *   <li><b>Type.</b> The setting is {@link SettingType#PASSWORD}-typed and the
 *       caller is on the restricted path (a script, a compose task, an agent).
 *       Re-typing it to {@code HIDDEN} makes it work.</li>
 *   <li><b>Key.</b> {@link SecretReferenceKeyPolicy} reserves the name for
 *       compiled server code, whatever its type and whoever asks — including
 *       connectors, which may otherwise read PASSWORD. Nothing makes this one
 *       work; the key is not meant to travel through a reference.</li>
 * </ul>
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

    private final @Nullable String key;

    public SecretAccessDeniedException(String key, SettingType type) {
        super("setting '" + key + "' is " + type + "-typed and cannot be resolved through a "
                + "secret reference: PASSWORD settings can neither be read nor overwritten by "
                + "an agent-reachable path. Use type HIDDEN if this value is meant for tool, "
                + "compose or script use.");
        this.key = key;
    }

    /**
     * Free-form variant for the write-side denials, which need their own wording
     * (overwrite refused / key reserved) rather than the read-side sentence.
     */
    public SecretAccessDeniedException(String message) {
        super(message);
        this.key = null;
    }

    /**
     * Free-form wording that still carries the key, so a caller rendering a
     * hint does not have to parse it back out of the sentence.
     */
    public SecretAccessDeniedException(String key, String message) {
        super(message);
        this.key = key;
    }

    /**
     * The setting key that was refused — for callers rendering a hint.
     * {@code null} for the free-form variant, whose message already names it.
     */
    public @Nullable String getKey() {
        return key;
    }
}
