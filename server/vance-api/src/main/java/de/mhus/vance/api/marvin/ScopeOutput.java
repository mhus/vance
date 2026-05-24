package de.mhus.vance.api.marvin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Output of a worker's SCOPE-phase LLM call. Field semantics
 * depend on {@link #action}:
 *
 * <ul>
 *   <li>{@link ScopeAction#CALL_RECIPE} — {@link #recipeCall} required.</li>
 *   <li>{@link ScopeAction#PROCEED_TO_CONCLUDE} — only {@link #reason}.</li>
 *   <li>{@link ScopeAction#NEEDS_SUBTASKS} — {@link #newTasks} non-empty.</li>
 *   <li>{@link ScopeAction#NEEDS_USER_INPUT} — {@link #userInput} required.</li>
 *   <li>{@link ScopeAction#BLOCKED_BY_PROBLEM} — {@link #problem} required.</li>
 * </ul>
 *
 * See {@code specification/marvin-engine.md} §4.1.
 */
public record ScopeOutput(
        ScopeAction action,
        @Nullable RecipeCall recipeCall,
        @Nullable List<NewTaskSpec> newTasks,
        @Nullable UserInputSpec userInput,
        @Nullable String problem,
        @Nullable String reason) {}
