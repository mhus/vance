package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for combining an engine's built-in system prompt with a
 * recipe-supplied {@code promptOverride}, plus the size-aware
 * variant selection. Centralised so every engine stays consistent —
 * composition rules are a Recipe-level concern, not an
 * engine-specific one.
 */
public final class SystemPrompts {

    private static final String SEPARATOR = "\n\n--- recipe extension ---\n\n";

    private SystemPrompts() {}

    /**
     * Returns the effective system-prompt text for one turn. Picks
     * the size-matched variant from the process and blends it with
     * the engine default per the recipe's {@link PromptMode}.
     *
     * <p>Variant selection:
     * <ul>
     *   <li>Engine has resolved a SMALL model AND
     *       {@code process.promptOverrideSmall} is set →
     *       use small variant.</li>
     *   <li>Otherwise → use {@code process.promptOverride}
     *       (the default-sized variant).</li>
     * </ul>
     *
     * <p>Composition by mode:
     * <ul>
     *   <li>No override → engine default unchanged.</li>
     *   <li>{@link PromptMode#APPEND} → engine default + separator + override.</li>
     *   <li>{@link PromptMode#OVERWRITE} → override alone (engine
     *       default discarded; the recipe must re-state any hard
     *       rules it cares about).</li>
     * </ul>
     */
    public static String compose(
            ThinkProcessDocument process,
            String engineDefault,
            ModelSize modelSize) {
        String override = pickOverride(process, modelSize);
        if (override == null || override.isBlank()) {
            return engineDefault;
        }
        PromptMode mode = process.getPromptMode() == null
                ? PromptMode.APPEND : process.getPromptMode();
        return switch (mode) {
            case OVERWRITE -> override;
            case APPEND -> engineDefault + SEPARATOR + override;
        };
    }

    /**
     * Convenience overload for engines that haven't resolved a model
     * size yet. Falls back to the default (large) variant — keeps
     * existing call sites working until they're upgraded.
     */
    public static String compose(ThinkProcessDocument process, String engineDefault) {
        return compose(process, engineDefault, ModelSize.LARGE);
    }

    private static @Nullable String pickOverride(
            ThinkProcessDocument process, ModelSize modelSize) {
        String small = process.getPromptOverrideSmall();
        if (modelSize == ModelSize.SMALL && small != null && !small.isBlank()) {
            return small;
        }
        return process.getPromptOverride();
    }
}
