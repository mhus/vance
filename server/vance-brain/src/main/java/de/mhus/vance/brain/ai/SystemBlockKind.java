package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.SystemMessage;

/**
 * How the {@link AnthropicRequestMapper}-equivalent logic should treat
 * a single {@link SystemMessage} for caching purposes.
 *
 * <p>Engines drive the {@code cache_control} placement by tagging each
 * system block they emit with one of these values; the Anthropic adapter
 * picks the <i>last</i> {@link #STATIC} block as the cache boundary.
 *
 * <p>{@link #STATIC} is the safe default — engines that haven't been
 * migrated yet keep using {@link SystemMessage} directly and behave
 * exactly as before (single block, marker on it). Once an engine starts
 * appending dynamic content (working-memory, plan-mode todos, recipe
 * catalog…) as a separate {@link de.mhus.vance.brain.ai.VanceSystemMessage}
 * with {@link #DYNAMIC}, the cache prefix only covers everything up to
 * the last static block — the dynamic tail is included in the request
 * but lives outside the cache hash.
 *
 * <p>See {@code specification/prompt-caching.md} §5 / §10.2 and
 * {@code readme/cache-layout-audit.md} §5 Option A for the layout
 * rationale.
 */
public enum SystemBlockKind {

    /**
     * Block content is bit-stable across calls of the same engine /
     * recipe. Belongs in the cache prefix. Default for any plain
     * {@link SystemMessage}.
     */
    STATIC,

    /**
     * Block content varies turn-to-turn (timestamps, working-memory,
     * plan-mode state, recipe catalogues). Must NOT be inside the
     * cache hash — otherwise every turn re-creates the cache.
     */
    DYNAMIC
}
