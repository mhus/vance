package de.mhus.vance.brain.ai;

/**
 * Where the {@code cache_control: ephemeral} marker is placed on the
 * outbound LLM request. Higher values include the levels above them.
 *
 * <p>Cache markers fix a prefix as a cache key — Anthropic charges
 * cache writes at ~1.25× normal input on the first call and cache
 * reads at ~10% on subsequent calls within TTL. Up to 4 markers per
 * request are supported.
 *
 * <p>Layout convention (see {@code specification/prompt-caching.md}):
 *
 * <pre>
 *   [system blocks]                ← marker 1 (boundary >= SYSTEM)
 *   [tool definitions]             ← marker 2 (boundary >= SYSTEM_AND_TOOLS)
 *   [skills / dynamic prefix]
 *   ──────── (cache boundary) ────
 *   [chat history, user message]   ← never cached
 * </pre>
 *
 * <p>For caching to actually hit, everything <i>up to</i> the marker
 * must be bit-stable across calls within a session. Engines that
 * inject timestamps, user IDs or pod IPs into the system prompt
 * <b>break their own cache</b> and should move that content into the
 * dynamic suffix.
 */
public enum CacheBoundary {

    /** No cache markers. Every call pays full input price. */
    NONE,

    /** Marker after the last system block. Tools / skills / messages
     *  remain dynamic. Useful when tools change frequently. */
    SYSTEM,

    /** Marker after the last system block <i>and</i> after the last
     *  tool definition. Default — covers the cheap, common win. */
    SYSTEM_AND_TOOLS;

    public boolean cachesSystem() {
        return this == SYSTEM || this == SYSTEM_AND_TOOLS;
    }

    public boolean cachesTools() {
        return this == SYSTEM_AND_TOOLS;
    }
}
