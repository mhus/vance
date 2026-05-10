package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;

/**
 * Helpers for combining an engine's built-in system prompt with the
 * recipe-supplied {@code promptOverride}. Both pieces are Pebble
 * templates — the renderer evaluates them with the per-turn context
 * (tier / model / provider / mode / profile / lang / params) before
 * the {@link PromptMode} blending kicks in.
 *
 * <p>Tier/model/mode/profile-aware variation lives <em>inside</em> the
 * template body now (Pebble {@code {% if tier == "small" %}…}). There
 * is no more dual-string {@code promptOverrideSmall}; the recipe
 * carries one templated string and the engine renders it per turn.
 */
public final class SystemPrompts {

    private static final String SEPARATOR = "\n\n--- recipe extension ---\n\n";

    private SystemPrompts() {}

    /**
     * Renders both pieces with {@code ctx} and blends them per
     * {@link PromptMode}. {@code engineDefault} is the unrendered
     * Pebble template that an engine ships as its built-in prompt.
     * {@code process.getPromptOverride()} is the recipe-supplied
     * (also Pebble) override.
     *
     * <ul>
     *   <li>No override → rendered engine default.</li>
     *   <li>{@link PromptMode#APPEND} → rendered default + separator + rendered override.</li>
     *   <li>{@link PromptMode#OVERWRITE} → rendered override alone.</li>
     * </ul>
     *
     * <p>{@code engineDefault} of {@code null} is treated as the empty
     * string — useful for engines whose persona <em>is</em> the
     * recipe (Eddie pattern).
     */
    public static String compose(
            ThinkProcessDocument process,
            String engineDefault,
            PromptTemplateRenderer renderer,
            Map<String, Object> ctx) {
        String renderedDefault = renderer.render(engineDefault, ctx);
        if (renderedDefault == null) renderedDefault = "";
        String renderedOverride = renderer.render(process.getPromptOverride(), ctx);
        if (renderedOverride == null || renderedOverride.isBlank()) {
            return renderedDefault;
        }
        PromptMode mode = process.getPromptMode() == null
                ? PromptMode.APPEND : process.getPromptMode();
        return switch (mode) {
            case OVERWRITE -> renderedOverride;
            case APPEND -> renderedDefault + SEPARATOR + renderedOverride;
        };
    }

    /**
     * Convenience overload for callers that don't need rendering — the
     * inputs are already-rendered text. Drops both the renderer and
     * the context. Used by tests and by code paths whose prompts are
     * known to be plain markdown without Pebble syntax.
     */
    public static String compose(ThinkProcessDocument process, String engineDefault) {
        String override = process.getPromptOverride();
        if (override == null || override.isBlank()) {
            return engineDefault == null ? "" : engineDefault;
        }
        PromptMode mode = process.getPromptMode() == null
                ? PromptMode.APPEND : process.getPromptMode();
        return switch (mode) {
            case OVERWRITE -> override;
            case APPEND -> (engineDefault == null ? "" : engineDefault) + SEPARATOR + override;
        };
    }
}
