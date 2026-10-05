package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.toolpack.research.SearchModality;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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

    // ── Shipped routing ───────────────────────────────────────────────
    /**
     * Out-of-the-box routing: the chain the dispatcher walks when no
     * {@code research.default.*} / {@code research.fallback.*} setting was
     * written. A setting for the modality overrides this entirely; an
     * explicitly empty setting means "no chain" (the dispatcher then uses
     * the assemble order).
     *
     * <p>The chains name instance ids, and an id without a configured
     * source document is skipped silently — that is what makes the shipped
     * chain degrade instead of fail: sources that arrive later (or never)
     * cost nothing and break nothing.
     *
     * <p>Order is <em>contract before keyless before self-hosted before
     * built-in</em>: a contracted instance answers first, a spent one steps
     * aside via the quota cooldown and the next candidate takes over.
     * Editable per tenant/project through the {@code research-routing}
     * setting form; this is the shipped proposal, not a law.
     */
    private static final Map<SearchModality, String> SHIPPED_DEFAULT = Map.of(
            SearchModality.WEB, "exa",
            SearchModality.NEWS, "exa",
            SearchModality.IMAGE, "firecrawl",
            SearchModality.VIDEO, "serper",
            SearchModality.PDF, "serper",
            SearchModality.ACADEMIC, "openalex",
            SearchModality.ENCYCLOPEDIA, "wikipedia",
            SearchModality.BOOK, "openlibrary");

    private static final Map<SearchModality, String> SHIPPED_FALLBACK = Map.of(
            SearchModality.WEB, "firecrawl,firecrawl-keyless,searxng,wikipedia,hackernews",
            SearchModality.NEWS, "firecrawl,hackernews,searxng",
            SearchModality.IMAGE, "serper,searxng",
            SearchModality.VIDEO, "searxng",
            SearchModality.PDF, "searxng",
            SearchModality.ACADEMIC, "pubmed,arxiv");

    /** Shipped default instance id for the modality, or {@code null} for none. */
    public static @Nullable String shippedDefault(SearchModality modality) {
        return SHIPPED_DEFAULT.get(modality);
    }

    /** Shipped fallback chain (comma-separated) for the modality, or {@code null}. */
    public static @Nullable String shippedFallbacks(SearchModality modality) {
        return SHIPPED_FALLBACK.get(modality);
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
