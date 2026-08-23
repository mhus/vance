package de.mhus.vance.brain.centauri;

/**
 * What is left of the Centauri setting surface now that an endpoint is a
 * document under {@code _vance/config/feeds/}: the subsystem's own knobs, and
 * the one per-endpoint value that is <em>not</em> configuration.
 *
 * <p>The actor salt stays a setting on purpose. It is generated server-side on
 * first use and written back, and an operator's configuration file is the
 * wrong place for a value the server owns: it would sit there in the clear, it
 * would drift against the hash a kit tracks the file by, and writing it would
 * fire a document-changed event that drops the very cache that just asked for
 * it.
 *
 * <p>Cascade is the standard {@code SettingService} cascade
 * (tenant → project → think-process).
 */
public final class CentauriSettings {

    private CentauriSettings() {
        /* constants only */
    }

    /**
     * Per-endpoint salt for the reader pseudonym (PASSWORD, generated
     * server-side on first use).
     *
     * <p>Per endpoint rather than global: a shared salt would let two sources
     * join their profiles over the same reader. Rotating it makes every reader
     * look new to that source.
     */
    public static String endpointActorSaltKey(String endpointId) {
        return "centauri.actorSalt." + endpointId;
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
