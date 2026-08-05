package de.mhus.vance.brain.script;

/**
 * Host-side hook a completion-guard script drives through
 * {@code vance.guard.continueWith(...)}. Kept in the {@code script}
 * package (rather than {@code guard}) so {@link VanceScriptApi} stays
 * free of a dependency on the guard subsystem; the
 * {@code CompletionGuardService} supplies the implementation when it
 * builds the {@code vance.guard} surface for a guard run.
 *
 * <p>The single operation is deliberately <em>cap-aware</em>: the
 * implementation increments the process's persistent {@code guardRounds}
 * counter and refuses (returns {@code false}) once {@code maxRounds} is
 * reached. That keeps the hard loop backstop in the service — a buggy
 * guard script cannot nudge the engine forever, no matter what it does.
 */
public interface GuardScriptHost {

    /**
     * Inject {@code prompt} into the process's own pending queue and
     * schedule another engine turn, so the engine keeps working instead
     * of yielding.
     *
     * @param prompt the follow-up prompt (non-blank)
     * @return {@code true} if the prompt was injected; {@code false}
     *         when the round cap is already reached (no injection)
     */
    boolean continueWith(String prompt);
}
