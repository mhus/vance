package de.mhus.vance.api.zaphod;

/**
 * Zaphod operating mode. Set from the recipe param
 * {@code sessionMode: true} at {@code start()} — never implied from
 * spawn context (a batch spawn without a goal must not silently
 * mutate into a chat).
 *
 * <ul>
 *   <li>{@link #BATCH} — the original one-shot semantics: the process
 *       runs its head rounds against {@code process.goal} once,
 *       synthesises, and closes {@code DONE}. Spawned by other
 *       engines (Arthur, Vogon, …) or by tools.</li>
 *   <li>{@link #SESSION} — reactive session-chat semantics: the process
 *       IS the session's chat process. It waits for user input, folds
 *       every pending {@code UserChatInput} into a per-turn
 *       {@code turnGoal}, drives the (long-lived) heads, emits the
 *       synthesis as the single chat reply, and re-arms instead of
 *       closing.</li>
 * </ul>
 *
 * <p>See {@code planning/zaphod-session-mode.md} and
 * {@code specification/public/zaphod-engine.md} §Session-Mode.
 */
public enum ZaphodMode {

    /** One-shot batch run — default, backward-compatible. */
    BATCH,

    /** Reactive session-chat — one council run per user turn. */
    SESSION
}
