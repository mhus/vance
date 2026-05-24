package de.mhus.vance.api.marvin;

/**
 * Specification for a CALL_RECIPE sub-process spawn. The worker
 * emits this when it wants the engine to invoke a specialist
 * recipe synchronously and feed the reply back as a USER message.
 *
 * @param recipe        recipe name (must be in
 *                      {@code params.availableRecipes})
 * @param steerContent  initial steer for the spawned sub-process
 */
public record RecipeCall(String recipe, String steerContent) {}
