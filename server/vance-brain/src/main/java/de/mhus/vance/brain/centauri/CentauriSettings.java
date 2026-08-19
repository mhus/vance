package de.mhus.vance.brain.centauri;

/**
 * Setting-key constants for the Centauri feed stack. Centralised so the
 * factory, the gate, the actor resolver and (later) the setting form share
 * one source of truth.
 *
 * <p>Cascade is the standard {@code SettingService} cascade
 * (tenant → project → think-process), read at project level by the factory.
 */
public final class CentauriSettings {

    private CentauriSettings() {
        /* constants only */
    }

    // ── Endpoint definitions ─────────────────────────────────────────
    public static final String PREFIX_ENDPOINT = "centauri.endpoint.";

    /** Suffix: protocol id (required) — "ode", "mastodon". */
    public static final String SUFFIX_PROTOCOL = ".protocol";

    /** Suffix: base URL (required). */
    public static final String SUFFIX_BASE_URL = ".baseUrl";

    /** Suffix: credential setting key (optional; PASSWORD). */
    public static final String SUFFIX_API_KEY = ".apiKey";

    /** Suffix: explicit on/off flag (default true). */
    public static final String SUFFIX_ENABLED = ".enabled";

    /**
     * Suffix: whether the reader pseudonym travels to this source
     * (default true).
     *
     * <p>Default {@code true} because the feature would otherwise be dead
     * by default and never exercised. The switch exists all the same: not
     * wanting one's readers profiled by a foreign source is a legitimate
     * position.
     */
    public static final String SUFFIX_SEND_ACTOR = ".sendActor";

    /**
     * Suffix: per-instance salt for the reader pseudonym (PASSWORD,
     * generated server-side on first use).
     *
     * <p>Per instance rather than global: a shared salt would let two
     * sources join their profiles over the same reader. Rotating it makes
     * every reader look new to that source.
     */
    public static final String SUFFIX_ACTOR_SALT = ".actorSalt";

    public static String endpointProtocolKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_PROTOCOL;
    }

    public static String endpointBaseUrlKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_BASE_URL;
    }

    public static String endpointApiKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_API_KEY;
    }

    public static String endpointEnabledKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_ENABLED;
    }

    public static String endpointSendActorKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_SEND_ACTOR;
    }

    public static String endpointActorSaltKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_ACTOR_SALT;
    }

    // ── Service-wide knobs ────────────────────────────────────────────
    public static final String FACTORY_CACHE_TTL_MINUTES = "centauri.factory.cache.ttlMinutes";

    /** Cooldown subject prefix: {@code centauri:<instanceId>}. */
    public static final String COOLDOWN_SUBJECT_PREFIX = "centauri:";

    /** Build the cooldown subject used in {@code ToolHealthService}. */
    public static String cooldownSubject(String instanceId) {
        return COOLDOWN_SUBJECT_PREFIX + instanceId;
    }
}
