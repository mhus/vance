package de.mhus.vance.brain.recipe;

import org.jspecify.annotations.Nullable;

/**
 * Lunkwill recipe block that wires a follow-up process to fire when
 * the worker reaches a stop signal. Generic by design — code-review,
 * memory-summarize, verify-by-test, security-audit all configure
 * differently flavoured hook-processes through this same record.
 *
 * <p>See {@code planning/lunkwill-post-completion-hook.md}.
 *
 * @param recipe        Hook-process recipe name (must exist, must be
 *                      a Lunkwill recipe, must not itself carry a
 *                      {@code postCompletionHook} block).
 * @param trigger       When the hook fires.
 * @param maxRounds     Hard cap on hook rounds per worker-process
 *                      lifetime. {@code 0} disables the hook entirely
 *                      (same effect as omitting the block).
 * @param goalTemplate  Optional Pebble template for the hook-process
 *                      goal. {@code null} ⇒ engine-default template.
 *                      Compile-validated at recipe-load.
 */
public record PostCompletionHookConfig(
        String recipe,
        PostCompletionHookTrigger trigger,
        int maxRounds,
        @Nullable String goalTemplate) {

    public PostCompletionHookConfig {
        if (recipe == null || recipe.isBlank()) {
            throw new IllegalArgumentException(
                    "postCompletionHook.recipe must be non-blank");
        }
        if (trigger == null) {
            throw new IllegalArgumentException(
                    "postCompletionHook.trigger must be set");
        }
        if (maxRounds < 0) {
            throw new IllegalArgumentException(
                    "postCompletionHook.maxRounds must be >= 0");
        }
    }
}
