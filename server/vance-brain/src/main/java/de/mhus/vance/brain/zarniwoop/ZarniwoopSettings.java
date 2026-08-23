package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.toolpack.research.SearchModality;
import java.util.Locale;

/**
 * What is left of the Zarniwoop setting surface now that an endpoint is a
 * document under {@code _vance/config/research/}: which endpoint serves a
 * modality by default, and the subsystem's own knobs.
 *
 * <p>Routing stays a setting on purpose — the key names a modality, which is an
 * enum value, so a form can render it. That was never true of
 * {@code research.endpoint.<id>.*}, where the id is part of the key.
 *
 * <p>Cascade is the standard {@code SettingService} cascade
 * (tenant → project → think-process).
 */
public final class ZarniwoopSettings {

    private ZarniwoopSettings() {
        /* constants only */
    }

    // ── Routing ───────────────────────────────────────────────────────
    public static final String PREFIX_DEFAULT = "research.default.";
    public static final String PREFIX_FALLBACK = "research.fallback.";

    /** Build {@code research.default.<modality-lowercase>}. */
    public static String defaultKey(SearchModality modality) {
        return PREFIX_DEFAULT + modality.name().toLowerCase(Locale.ROOT);
    }

    /** Build {@code research.fallback.<modality-lowercase>}. */
    public static String fallbackKey(SearchModality modality) {
        return PREFIX_FALLBACK + modality.name().toLowerCase(Locale.ROOT);
    }

    // ── Service-wide knobs ────────────────────────────────────────────
    public static final String QUOTA_CACHE_TTL_MINUTES = "research.quota.cache.ttlMinutes";
    public static final String FACTORY_CACHE_TTL_MINUTES = "research.factory.cache.ttlMinutes";
    public static final String LOG_RETENTION_DAYS = "research.log.retentionDays";

    /** Cooldown subject prefix: {@code research:<instanceId>:<modality>}. */
    public static final String COOLDOWN_SUBJECT_PREFIX = "research:";

    /** Build the cooldown subject used in {@code ToolHealthService}. */
    public static String cooldownSubject(String instanceId, SearchModality modality) {
        return COOLDOWN_SUBJECT_PREFIX + instanceId + ":" + modality.name();
    }
}
