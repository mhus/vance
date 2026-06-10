package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.toolpack.research.SearchModality;
import java.util.Locale;

/**
 * Setting-key constants for the Zarniwoop search/research stack.
 * Centralised here so the factory, dispatcher, frontend tools and
 * (later) the research recipes share one source of truth and
 * settings-editors can resolve cross-references.
 *
 * <p>Cascade is the standard {@code SettingService} cascade
 * (tenant → project → think-process).
 */
public final class ZarniwoopSettings {

    private ZarniwoopSettings() {
        /* constants only */
    }

    // ── Endpoint definitions ─────────────────────────────────────────
    public static final String PREFIX_ENDPOINT = "research.endpoint.";

    /** Suffix: protocol id (required). */
    public static final String SUFFIX_PROTOCOL = ".protocol";

    /** Suffix: base URL (required for HTTP-based protocols). */
    public static final String SUFFIX_BASE_URL = ".baseUrl";

    /** Suffix: credential setting key (optional). */
    public static final String SUFFIX_API_KEY = ".apiKey";

    /** Suffix: explicit on/off flag (default true). */
    public static final String SUFFIX_ENABLED = ".enabled";

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

    /** Build {@code research.endpoint.<id>.protocol}. */
    public static String endpointProtocolKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_PROTOCOL;
    }

    /** Build {@code research.endpoint.<id>.baseUrl}. */
    public static String endpointBaseUrlKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_BASE_URL;
    }

    /** Build {@code research.endpoint.<id>.apiKey}. */
    public static String endpointApiKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_API_KEY;
    }

    /** Build {@code research.endpoint.<id>.enabled}. */
    public static String endpointEnabledKey(String endpointId) {
        return PREFIX_ENDPOINT + endpointId + SUFFIX_ENABLED;
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
