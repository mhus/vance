package de.mhus.vance.brain.prompt;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds the variable map handed to {@link PromptTemplateRenderer}.
 * Centralises the contract between Java callers (engines, recipe
 * loader) and template authors — the keys exposed here are the only
 * ones a recipe / engine prompt may rely on.
 *
 * <h2>Exposed variables</h2>
 * <ul>
 *   <li>{@code tier} — {@code "small"} / {@code "large"}, lowercase.
 *       Drives the primary size branch.</li>
 *   <li>{@code model} — resolved model name verbatim, e.g.
 *       {@code "claude-sonnet-4-6"}, {@code "gemini-2.5-flash"}.
 *       For regex matches via {@code matches} / {@code is matching()}.</li>
 *   <li>{@code provider} — lowercase provider key, e.g.
 *       {@code "anthropic"}, {@code "google"}, {@code "openai"}.</li>
 *   <li>{@code mode} — {@link ProcessMode} name, e.g. {@code "NORMAL"},
 *       {@code "EXPLORING"}, {@code "PLANNING"}, {@code "EXECUTING"}.</li>
 *   <li>{@code profile} — connection profile, e.g. {@code "foot"},
 *       {@code "web"}, {@code "default"}.</li>
 *   <li>{@code recipe} — current recipe name.</li>
 *   <li>{@code engine} — engine name backing the recipe.</li>
 *   <li>{@code lang} — chat language code, e.g. {@code "de"},
 *       {@code "en"}. Empty until the language-settings work lands
 *       (see {@code instructions/_todo.md}).</li>
 *   <li>{@code params} — the merged recipe params map (read-only),
 *       so templates can read e.g. {@code {{ params.maxIterations }}}.</li>
 * </ul>
 *
 * <p>Unset values default to empty strings via the renderer's lenient
 * mode — a template referencing {@code {{ lang }}} on a process where
 * {@code lang} hasn't been wired yet renders the empty string, not a
 * runtime error.
 *
 * <p>Builder is non-thread-safe and intended for single use per render.
 */
public final class PromptContextBuilder {

    private final Map<String, Object> map = new LinkedHashMap<>();

    private PromptContextBuilder() {}

    public static PromptContextBuilder create() {
        return new PromptContextBuilder();
    }

    /**
     * Convenience factory used by engines: pulls
     * {@code recipe / mode / profile / params} from the process and
     * {@code tier / model / provider} from the resolved model. Caller
     * still adds {@code engine} (each engine has its own {@code NAME}
     * constant) and any tenant-specific extras like {@code lang}.
     *
     * <p>{@code modelInfo} may be {@code null} when the engine couldn't
     * resolve the model (e.g. catalog miss); the tier/model/provider
     * fields then stay unset and templates that reference them render
     * empty in lenient mode.
     */
    public static PromptContextBuilder forProcess(
            ThinkProcessDocument process, @Nullable ModelInfo modelInfo) {
        PromptContextBuilder b = new PromptContextBuilder();
        if (process != null) {
            b.recipe(process.getRecipeName());
            b.mode(process.getMode());
            b.profile(process.getConnectionProfile());
            b.params(process.getEngineParams());
        }
        if (modelInfo != null) {
            b.tier(modelInfo.size());
            b.model(modelInfo.modelName());
            b.provider(modelInfo.provider());
        }
        return b;
    }

    public PromptContextBuilder tier(@Nullable ModelSize size) {
        if (size != null) map.put("tier", size.name().toLowerCase());
        return this;
    }

    public PromptContextBuilder tier(@Nullable String tier) {
        if (tier != null && !tier.isBlank()) map.put("tier", tier.toLowerCase());
        return this;
    }

    public PromptContextBuilder model(@Nullable String model) {
        if (model != null && !model.isBlank()) map.put("model", model);
        return this;
    }

    public PromptContextBuilder provider(@Nullable String provider) {
        if (provider != null && !provider.isBlank()) {
            map.put("provider", provider.toLowerCase());
        }
        return this;
    }

    public PromptContextBuilder mode(@Nullable ProcessMode mode) {
        if (mode != null) map.put("mode", mode.name());
        return this;
    }

    public PromptContextBuilder mode(@Nullable String mode) {
        if (mode != null && !mode.isBlank()) map.put("mode", mode);
        return this;
    }

    public PromptContextBuilder profile(@Nullable String profile) {
        if (profile != null && !profile.isBlank()) map.put("profile", profile);
        return this;
    }

    public PromptContextBuilder recipe(@Nullable String recipe) {
        if (recipe != null && !recipe.isBlank()) map.put("recipe", recipe);
        return this;
    }

    public PromptContextBuilder engine(@Nullable String engine) {
        if (engine != null && !engine.isBlank()) map.put("engine", engine);
        return this;
    }

    public PromptContextBuilder lang(@Nullable String lang) {
        if (lang != null && !lang.isBlank()) map.put("lang", lang);
        return this;
    }

    /**
     * Pre-rendered profile-block append text — exposed to recipe
     * templates as {@code {{ profileAppend }}} so the author can place
     * it at any position in the prompt body. Normally set by
     * {@link de.mhus.vance.brain.thinkengine.SystemPrompts#compose}, not
     * by engines directly. See {@code planning/prompt-inlining.md} §3.
     */
    public PromptContextBuilder profileAppend(@Nullable String profileAppend) {
        if (profileAppend != null) map.put("profileAppend", profileAppend);
        return this;
    }

    public PromptContextBuilder params(@Nullable Map<String, Object> params) {
        if (params != null && !params.isEmpty()) {
            // Defensive copy — recipe params can be mutated by other
            // consumers in the same turn (lane scheduler, progress
            // emitter), and we don't want a template render to see
            // half-updated state.
            map.put("params", new HashMap<>(params));
        }
        return this;
    }

    public Map<String, Object> build() {
        return Map.copyOf(map);
    }
}
