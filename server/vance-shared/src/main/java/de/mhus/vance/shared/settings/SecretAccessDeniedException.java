package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when an authored {@code {{secret:…}}} reference tries to resolve a
 * setting it may not read, or when {@link SettingService#set} refuses a write
 * the {@link VancetopePolicy} forbids. Four independent reasons:
 *
 * <ul>
 *   <li><b>Type.</b> The setting is {@link SettingType#PASSWORD}-typed and the
 *       caller is on the restricted path (a script, a compose task, an agent).
 *       Re-typing it to {@code HIDDEN} makes it work.</li>
 *   <li><b>Reference key.</b> {@link SecretReferenceKeyPolicy} reserves the
 *       name for compiled server code, whatever its type and whoever asks —
 *       including connectors, which may otherwise read PASSWORD. Nothing makes
 *       this one work; the key is not meant to travel through a reference.</li>
 *   <li><b>System namespace.</b> {@link VancetopePolicy#isSystemKey} — the key
 *       is in the {@code vancetope.*} namespace, which is operator-managed and
 *       not writable via {@code set}.</li>
 *   <li><b>Tenant / project deny list.</b> The key matches a pattern in
 *       {@code vancetope.denied.tenant} or {@code vancetope.denied.project}
 *       in the {@code _tenant} project.</li>
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

    /**
     * Why a setting write was refused. Carried on the exception so callers
     * (anus CLI, controller advice, audit rendering) can pick a structured
     * message without parsing the sentence. The {@link #getMessage()} still
     * carries the full human-readable explanation + resolution; this enum is
     * for programmatic dispatch.
     */
    public enum Reason {
        /** Key is PASSWORD-typed and the reference path may not resolve it. */
        REFERENCE_PASSWORD_TYPE,
        /** Key is reserved by SecretReferenceKeyPolicy. */
        REFERENCE_RESERVED_KEY,
        /** Key is in the {@code vancetope.*} namespace and {@code set} is policy-bound. */
        WRITE_SYSTEM_NAMESPACE,
        /** Key matches vancetope.denied.tenant for a write to the _tenant project. */
        WRITE_TENANT_DENY_LIST,
        /** Key matches vancetope.denied.project for a write to a user project. */
        WRITE_PROJECT_DENY_LIST,
        /** {@code forceSet} was used for a vancetope.* key outside the .denied. sub-prefix. */
        WRITE_FORCE_NON_META,
        /** {@code setAgentSecret} tried to overwrite a PASSWORD that an agent may not read. */
        AGENT_OVERWRITE_PASSWORD,
        /** {@code setAgentSecret} tried to write a key reserved for operator configuration. */
        AGENT_RESERVED_KEY,
        /** Pre-existing reason: free-form wording without structured dispatch. */
        OTHER
    }

    private final @Nullable String key;
    private final Reason reason;

    public SecretAccessDeniedException(String key, SettingType type) {
        super("setting '" + key + "' is " + type + "-typed and cannot be resolved through a "
                + "secret reference: PASSWORD settings can neither be read nor overwritten by "
                + "an agent-reachable path. Use type HIDDEN if this value is meant for tool, "
                + "compose or script use.");
        this.key = key;
        this.reason = Reason.REFERENCE_PASSWORD_TYPE;
    }

    /**
     * Free-form variant for the write-side denials, which need their own wording
     * (overwrite refused / key reserved) rather than the read-side sentence.
     */
    public SecretAccessDeniedException(String message) {
        super(message);
        this.key = null;
        this.reason = Reason.OTHER;
    }

    /**
     * Free-form wording that still carries the key, so a caller rendering a
     * hint does not have to parse it back out of the sentence.
     */
    public SecretAccessDeniedException(String key, String message) {
        super(message);
        this.key = key;
        this.reason = Reason.OTHER;
    }

    /**
     * Structured variant: the caller passes a {@link Reason} so a renderer
     * (anus CLI, REST advice) can pick the right user-facing wording without
     * pattern-matching on the sentence. The {@code message} is still the
     * complete human-readable explanation including the resolution hint.
     */
    public SecretAccessDeniedException(Reason reason, String key, String message) {
        super(message);
        this.key = key;
        this.reason = reason;
    }

    /**
     * The setting key that was refused — for callers rendering a hint.
     * {@code null} for the free-form variant, whose message already names it.
     */
    public @Nullable String getKey() {
        return key;
    }

    /**
     * Why the write was refused — see {@link Reason}. Use this for
     * programmatic dispatch (different REST status codes, different CLI
     * hint messages); the full sentence is in {@link #getMessage()}.
     */
    public Reason getReason() {
        return reason;
    }
}
