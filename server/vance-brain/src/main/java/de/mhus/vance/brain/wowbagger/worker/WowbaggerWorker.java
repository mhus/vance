package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.recipe.ResolvedRecipe;

/**
 * A worker type — one Spring bean each. {@link WowbaggerWorkerRegistry}
 * picks the first type whose {@link #supports} accepts the run's worker
 * recipe; recipes no type accepts are refused at configure time.
 */
public interface WowbaggerWorker {

    /** Short type name, also {@link PreparedWorker#kind()}. */
    String kind();

    /** One line for refusal messages: which recipes this type runs. */
    String describe();

    boolean supports(ResolvedRecipe recipe);

    /**
     * Resolve and check everything that does not depend on the records.
     * Throws {@link IllegalStateException} with an agent-readable reason when
     * the recipe cannot run (bad profile, unresolvable model, …).
     */
    PreparedWorker prepare(WorkerSetup setup);
}
