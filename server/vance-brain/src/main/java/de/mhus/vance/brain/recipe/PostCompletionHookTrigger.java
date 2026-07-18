package de.mhus.vance.brain.recipe;

import de.mhus.vance.brain.frankie.FrankieEngine;

/**
 * When the Frankie post-completion hook fires.
 *
 * <p>See {@code planning/frankie-post-completion-hook.md} for the
 * full design. The trigger is configured per-recipe via
 * {@code postCompletionHook.trigger} and consumed by
 * {@link FrankieEngine}.
 */
public enum PostCompletionHookTrigger {

    /**
     * Fire after the LLM returns a final reply with no tool calls
     * (natural stop). The most common case — the worker thinks it
     * is done and the hook gets the last word.
     */
    NATURAL_STOP,

    /**
     * Fire after a tool result carries {@code _terminate: true}.
     * Rare — tool-terminate is a deliberate done-signal, hooking it
     * means the worker can't unilaterally close out.
     */
    TERMINATE,

    /** Fire on both natural stop and tool-terminate. */
    BOTH;

    /** True iff this trigger fires on natural-stop. */
    public boolean firesOnNaturalStop() {
        return this == NATURAL_STOP || this == BOTH;
    }

    /** True iff this trigger fires on tool-driven terminate. */
    public boolean firesOnTerminate() {
        return this == TERMINATE || this == BOTH;
    }
}
