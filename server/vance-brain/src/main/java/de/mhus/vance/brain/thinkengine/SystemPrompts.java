package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.Map;

/**
 * Helpers for combining an engine's built-in system prompt with the
 * recipe-supplied {@code promptOverride} and the optional profile-
 * block append. All three pieces are Pebble templates — the renderer
 * evaluates them with the per-turn context (tier / model / provider /
 * mode / profile / lang / params, plus the rendered profile-append
 * exposed as {@code profileAppend}) before the {@link PromptMode}
 * blending kicks in.
 *
 * <p>Tier/model/mode/profile-aware variation lives <em>inside</em> the
 * template body now (Pebble {@code {% if tier == "small" %}…}). There
 * is no more dual-string {@code promptOverrideSmall}; the recipe
 * carries one templated string and the engine renders it per turn.
 *
 * <p>Profile-append placement: see {@code planning/prompt-inlining.md}
 * §3. The recipe template can use {@code {{ profileAppend }}} to
 * splice the profile-block append at any position. When the template
 * doesn't reference the variable AND the profile-append is non-blank,
 * the renderer falls back to legacy auto-append at the end —
 * backwards-compatible default.
 */
public final class SystemPrompts {

    private static final String SEPARATOR = "\n\n--- recipe extension ---\n\n";

    /** Token searched for in the recipe template to detect explicit placement. */
    static final String PROFILE_APPEND_VAR = "profileAppend";

    /** Token searched for in the engine default / recipe override to detect
     *  explicit placement of the addon-fragment block. */
    static final String ADDON_SECTIONS_VAR = "addonSections";

    private SystemPrompts() {}

    /**
     * Renders all three pieces with {@code ctx} and blends them per
     * {@link PromptMode}. {@code engineDefault} is the unrendered
     * Pebble template that an engine ships as its built-in prompt.
     * {@code process.getPromptOverride()} is the recipe-supplied
     * (also Pebble) override; {@code process.getPromptOverrideAppend()}
     * is the profile-block append.
     *
     * <p>The profile-append is rendered first so its content can be
     * exposed as the {@code profileAppend} variable in the recipe
     * template's render context. If the recipe references that
     * variable, it controls placement and the renderer skips auto-
     * append. Otherwise the rendered append is glued to the end of
     * the rendered override (legacy behaviour).
     *
     * <ul>
     *   <li>No override → rendered engine default (with profile-append
     *       glued to the end if non-blank, since there's no template to
     *       reference {@code profileAppend} from).</li>
     *   <li>{@link PromptMode#APPEND} → rendered default + separator + composed override.</li>
     *   <li>{@link PromptMode#OVERWRITE} → composed override alone.</li>
     * </ul>
     */
    /**
     * Renders the engine default, the recipe override and the profile-
     * block append against {@code ctx} and blends them per
     * {@link PromptMode}. Engines hand any addon-supplied prompt block
     * in via {@link de.mhus.vance.brain.prompt.PromptContextBuilder#addonSections}
     * — this method reads the value out of {@code ctx} under the
     * {@code addonSections} key and applies the auto-append rule below.
     *
     * <p>Addon-block placement:
     * <ul>
     *   <li>If the engine default template references
     *       {@code {{ addonSections }}}, the variable controls position
     *       — the renderer substitutes verbatim, no auto-append.</li>
     *   <li>Otherwise, when the addon block is non-blank, it is auto-
     *       appended to the rendered engine default with a blank-line
     *       separator (analog to the legacy {@code profileAppend}
     *       behaviour, scoped to the engine layer).</li>
     *   <li>The recipe override may also reference {@code {{ addonSections }}}
     *       to embed the block, but no auto-append happens in the
     *       override layer — engine-scoped material belongs to the
     *       engine prompt, and a {@code OVERWRITE}-mode override
     *       opting out of it is an explicit recipe-author choice.</li>
     * </ul>
     */
    public static String compose(
            ThinkProcessDocument process,
            String engineDefault,
            PromptTemplateRenderer renderer,
            Map<String, Object> ctx) {

        // Render the profile-append once and expose it to subsequent
        // renders as {{ profileAppend }}. We use a copy so the caller's
        // ctx isn't mutated.
        String appendRaw = process.getPromptOverrideAppend();
        String appendRendered = renderer.render(appendRaw, ctx);

        // The caller (engine) places the addon block via
        // PromptContextBuilder.addonSections(...) — we just read.
        Object addonRaw = ctx.get(ADDON_SECTIONS_VAR);
        String addonContent = addonRaw instanceof String s ? s : "";

        Map<String, Object> ctxWithAppend = new HashMap<>(ctx);
        ctxWithAppend.put(PROFILE_APPEND_VAR,
                appendRendered == null ? "" : appendRendered);

        String renderedDefault = renderer.render(engineDefault, ctxWithAppend);
        if (renderedDefault == null) renderedDefault = "";

        // Auto-append addon block when the engine default template does
        // not place {{ addonSections }} itself. Mirrors the legacy
        // profile-append auto-glue, but lives one layer up (engine
        // prompt, not recipe override).
        if (!addonContent.isBlank()
                && (engineDefault == null
                        || !engineDefault.contains(ADDON_SECTIONS_VAR))) {
            renderedDefault = renderedDefault.isBlank()
                    ? addonContent
                    : renderedDefault + "\n\n" + addonContent;
        }

        String overrideRaw = process.getPromptOverride();
        String renderedOverride = renderer.render(overrideRaw, ctxWithAppend);

        String composedOverride = composeOverrideWithAppend(
                overrideRaw, renderedOverride, appendRendered);

        if (composedOverride == null || composedOverride.isBlank()) {
            // No override at all → engine default + profile-append at the
            // end (the engine default doesn't get the {{ profileAppend }}
            // variable substitution since it's typically a pure engine
            // prompt, not recipe-scope).
            if (appendRendered != null && !appendRendered.isBlank()) {
                return renderedDefault + SEPARATOR + appendRendered;
            }
            return renderedDefault;
        }
        PromptMode mode = process.getPromptMode() == null
                ? PromptMode.APPEND : process.getPromptMode();
        return switch (mode) {
            case OVERWRITE -> composedOverride;
            case APPEND -> renderedDefault + SEPARATOR + composedOverride;
        };
    }

    /**
     * Decides whether the recipe template handled profile-append
     * placement itself (variable reference) or whether we need to
     * auto-append at the end.
     *
     * <p>Heuristic: a {@code String.contains(...)} on the unrendered
     * template source. False-positive rate is negligible — the token
     * {@code profileAppend} is too specific to appear in normal recipe
     * prose, and the worst-case ("author meant the word literally")
     * shows up immediately on first turn as a missing append.
     */
    private static String composeOverrideWithAppend(
            String overrideRaw, String renderedOverride, String appendRendered) {
        if (renderedOverride == null) return null;
        if (appendRendered == null || appendRendered.isBlank()) {
            return renderedOverride;
        }
        if (overrideRaw != null && overrideRaw.contains(PROFILE_APPEND_VAR)) {
            // Template explicitly placed {{ profileAppend }} somewhere —
            // it's already in renderedOverride, no auto-append.
            return renderedOverride;
        }
        if (renderedOverride.isBlank()) {
            return appendRendered;
        }
        return renderedOverride + "\n\n" + appendRendered;
    }

    /**
     * Convenience overload for callers that don't need rendering — the
     * inputs are already-rendered text, the legacy concat is applied
     * verbatim, and the profile-append (if any) is glued to the end.
     * Used by tests and by code paths whose prompts are known to be
     * plain markdown without Pebble syntax.
     */
    public static String compose(ThinkProcessDocument process, String engineDefault) {
        String override = process.getPromptOverride();
        String append = process.getPromptOverrideAppend();
        String fullOverride;
        if (override == null || override.isBlank()) {
            fullOverride = (append == null || append.isBlank()) ? null : append;
        } else if (append == null || append.isBlank()) {
            fullOverride = override;
        } else {
            fullOverride = override + "\n\n" + append;
        }
        if (fullOverride == null || fullOverride.isBlank()) {
            return engineDefault == null ? "" : engineDefault;
        }
        PromptMode mode = process.getPromptMode() == null
                ? PromptMode.APPEND : process.getPromptMode();
        return switch (mode) {
            case OVERWRITE -> fullOverride;
            case APPEND -> (engineDefault == null ? "" : engineDefault) + SEPARATOR + fullOverride;
        };
    }
}
