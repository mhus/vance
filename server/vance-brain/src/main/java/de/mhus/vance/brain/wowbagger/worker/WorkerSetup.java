package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/** Input of {@link WowbaggerWorker#prepare}: the run process, the spec, the resolved worker recipe. */
public record WorkerSetup(ThinkProcessDocument process, WorkerSpec spec, ResolvedRecipe recipe) {}
