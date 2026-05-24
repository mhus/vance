package de.mhus.vance.api.marvin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Output of a worker's REFLECT-phase LLM call. Same shape as
 * {@link ScopeOutput} but with {@link ReflectAction} and
 * iteration-bounded by {@code params.reflectMaxIterations}
 * (default 3).
 *
 * <p>See {@code specification/marvin-engine.md} §4.2.
 */
public record ReflectOutput(
        ReflectAction action,
        @Nullable RecipeCall recipeCall,
        @Nullable List<NewTaskSpec> newTasks,
        @Nullable UserInputSpec userInput,
        @Nullable String problem,
        @Nullable String reason) {}
