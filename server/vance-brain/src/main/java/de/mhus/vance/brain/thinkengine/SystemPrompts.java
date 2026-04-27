package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for combining an engine's built-in system prompt with a
 * recipe-supplied {@code promptOverride}. Centralised so every
 * engine stays consistent — composition rules are a Recipe-level
 * concern, not an engine-specific one.
 */
public final class SystemPrompts {

    private static final String SEPARATOR = "\n\n--- recipe extension ---\n\n";

    private SystemPrompts() {}

    /**
     * Returns the effective system-prompt text for one turn, blending
     * the engine default with the process's recipe override:
     *
     * <ul>
     *   <li>No override → engine default unchanged.</li>
     *   <li>{@link PromptMode#APPEND} → engine default + separator + override.</li>
     *   <li>{@link PromptMode#OVERWRITE} → override alone (engine
     *       default discarded; the recipe must re-state any hard
     *       rules it cares about).</li>
     * </ul>
     */
    public static String compose(ThinkProcessDocument process, String engineDefault) {
        String override = process.getPromptOverride();
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

    /** Convenience for engines that build the default lazily. */
    public static String compose(
            ThinkProcessDocument process,
            @Nullable String engineDefault,
            String fallbackIfBothMissing) {
        if (engineDefault == null) engineDefault = fallbackIfBothMissing;
        return compose(process, engineDefault);
    }
}
