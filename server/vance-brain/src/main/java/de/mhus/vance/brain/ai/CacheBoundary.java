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
 * <p>Layout convention (see {@code specification/public/prompt-caching.md}):
 *
 * <pre>
 *   [static system blocks]         ← marker 1 (boundary >= SYSTEM)
 *   [tool definitions]             ← marker 2 (boundary >= SYSTEM_AND_TOOLS)
 *   ──────── (cache boundary) ────
 *   [chat history]                 ← marker 3 (boundary >= …_TAIL)
 *   ──────── (cache boundary) ────
 *   [dynamic system tail]          ← never cached
 * </pre>
 *
 * <p>For caching to actually hit, everything <i>up to</i> a marker must be
 * bit-stable across calls within a session. Engines that inject timestamps,
 * user IDs or pod IPs into the system prompt <b>break their own cache</b>
 * and should tag that content {@link SystemBlockKind#DYNAMIC} instead.
 *
 * <p>The history marker only pays off when the dynamic system blocks are out
 * of the top-level {@code system} array — an edit there invalidates the
 * message cache along with the system cache. The Anthropic mapper therefore
 * demotes {@link #SYSTEM_AND_TOOLS_AND_TAIL} to {@link #SYSTEM_AND_TOOLS} for
 * models that cannot carry mid-conversation system messages.
 */
public enum CacheBoundary {

    /** No cache markers. Every call pays full input price. */
    NONE,

    /** Marker after the last system block. Tools / skills / messages
     *  remain dynamic. Useful when tools change frequently. */
    SYSTEM,

    /** Marker after the last system block <i>and</i> after the last
     *  tool definition. Default for models without history caching. */
    SYSTEM_AND_TOOLS,

    /**
     * Additionally a marker on the last content block of the conversation
     * history — the growing context is then read at cache-read prices
     * instead of being re-billed at full input price every round-trip. Only
     * placed when the model supports mid-conversation system messages
     * ({@link ModelCapability#MID_CONVERSATION_SYSTEM}); otherwise the
     * mapper falls back to {@link #SYSTEM_AND_TOOLS}.
     */
    SYSTEM_AND_TOOLS_AND_TAIL;

    public boolean cachesSystem() {
        return this != NONE;
    }

    public boolean cachesTools() {
        return this == SYSTEM_AND_TOOLS || this == SYSTEM_AND_TOOLS_AND_TAIL;
    }

    /** Marker on the last content block of the conversation history. */
    public boolean cachesTail() {
        return this == SYSTEM_AND_TOOLS_AND_TAIL;
    }

    /**
     * The same boundary without the history marker — the effective value for
     * models that cannot keep a mutable system tail out of the cache hash.
     */
    public CacheBoundary withoutTail() {
        return cachesTail() ? SYSTEM_AND_TOOLS : this;
    }
}
