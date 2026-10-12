package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Chooses the worker type for a run's worker recipe and prepares it. Only
 * light workers exist — a recipe for a spawnable chat or strategy engine is
 * refused here, with the list of what Wowbagger does run.
 */
@Service
public class WowbaggerWorkerRegistry {

    private final List<WowbaggerWorker> workers;
    private final RecipeResolver recipeResolver;

    public WowbaggerWorkerRegistry(List<WowbaggerWorker> workers, RecipeResolver recipeResolver) {
        this.workers = List.copyOf(workers);
        this.recipeResolver = recipeResolver;
    }

    /**
     * Resolve the worker recipe, pick its type, prepare it.
     *
     * @throws IllegalStateException recipe missing, not a light worker, or
     *                               refused by the type's own preparation
     */
    public PreparedWorker prepare(ThinkProcessDocument process, WorkerSpec spec) {
        ResolvedRecipe recipe = recipeResolver
                .resolve(process.getTenantId(), process.getProjectId(), spec.recipeName())
                .orElseThrow(() -> new IllegalStateException(
                        "worker recipe '" + spec.recipeName() + "' not found in the recipe cascade"));
        WowbaggerWorker worker = workers.stream()
                .filter(w -> w.supports(recipe))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("worker recipe '" + recipe.name() + "' (engine "
                        + recipe.engine() + (recipe.internal() ? ", internal" : "")
                        + ") is not a Wowbagger worker — Wowbagger runs light workers only: "
                        + describeAll()));
        return worker.prepare(new WorkerSetup(process, spec, recipe));
    }

    /** All worker types, for refusal messages and the agent's status block. */
    public String describeAll() {
        return workers.stream().map(w -> w.kind() + " (" + w.describe() + ")").collect(Collectors.joining("; "));
    }
}
