package de.mhus.vance.brain.ai;

/**
 * Cache lifetime requested on the {@code cache_control} marker.
 *
 * <ul>
 *   <li>{@link #DEFAULT_5MIN} — 5 minutes, no extra cost. Right for
 *       interactive sessions where turns happen within a few minutes.</li>
 *   <li>{@link #LONG_1H} — 1 hour, costs ~2× write price up-front
 *       but pays off for cached prefixes that span longer idle
 *       periods (overnight assistants, scheduled agents). Requires
 *       the {@code anthropic-beta: extended-cache-ttl-2025-04-11}
 *       header — set automatically by the adapter when this value
 *       is selected.</li>
 * </ul>
 *
 * <p>v1: choice is per-call, defaulted in {@code AiChatOptions}. The
 * tenant-level allowlist for the 1h TTL (so individual recipes can
 * opt in) is tracked as a follow-up — see prompt-caching.md §11.1.
 */
public enum CacheTtl {

    DEFAULT_5MIN,
    LONG_1H
}
