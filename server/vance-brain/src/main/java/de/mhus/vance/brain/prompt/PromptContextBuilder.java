package de.mhus.vance.brain.prompt;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
 *   <li>{@code has_<type>_rootdir} — one boolean per workspace RootDir
 *       type present in the current project, e.g.
 *       {@code has_python_rootdir}, {@code has_git_rootdir}. Set by
 *       {@link #withRootDirTypes}; unset entries are falsy in lenient
 *       mode. Templates use these to gate type-specific tool hints.</li>
 * </ul>
 *
 * <p>Unset values default to empty strings via the renderer's lenient
 * mode — a template referencing {@code {{ lang }}} on a process where
 * {@code lang} hasn't been wired yet renders the empty string, not a
 * runtime error.
 *
 * <p>Builder is non-thread-safe and scoped to a single render. Calling
 * {@link #build()} more than once is fine — each call returns a fresh
 * immutable snapshot of the current state, so an engine can extract a
 * base context, render addon fragments against it, set
 * {@link #addonSections(String)} on the same builder and build again.
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
     * Voice-mode flag for the current turn — {@code true} when the
     * client expected a TTS-friendly reply for the most recent
     * USER_CHAT_INPUT in the drain-batch (speaker / talk-mode on).
     * Engines render a {@code {% if voiceMode %}} block in their
     * system prompt accordingly. See {@code specification/voice-mode.md}.
     *
     * <p>Per-turn, never persisted. Default {@code false} when the
     * setter is not called.
     */
    public PromptContextBuilder voiceMode(boolean voiceMode) {
        map.put("voiceMode", voiceMode);
        return this;
    }

    /**
     * Multi-user collaboration mode for the current turn — {@code true}
     * when the session has {@code allowMultipleClients} <em>and</em>
     * more than one client is currently bound (so the agent must
     * disambiguate participants and only respond when addressed). See
     * {@code planning/multi-user-sessions.md} §5.
     *
     * <p>Per-turn — never persisted on the process. Default {@code false}.
     */
    public PromptContextBuilder collabActive(boolean collabActive) {
        map.put("collabActive", collabActive);
        return this;
    }

    /**
     * Display names of every participant currently bound to the
     * session — rendered into the prompt as the {@code participants}
     * variable when {@code collabActive} is true. Falls back to user
     * ids if the display name was not captured at register time.
     */
    public PromptContextBuilder participants(java.util.@Nullable List<String> participants) {
        if (participants != null && !participants.isEmpty()) {
            map.put("participants", java.util.List.copyOf(participants));
        }
        return this;
    }

    /**
     * Display name of the participant whose USER turn drove this
     * drain-batch — rendered into the prompt as
     * {@code mentionedBy}. Lets the agent address the right person
     * by name in its reply. {@code null} for autonomous wake paths
     * (Auto-Wakeup, tool results) and for legacy turns.
     */
    public PromptContextBuilder mentionedBy(@Nullable String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            map.put("mentionedBy", displayName);
        }
        return this;
    }

    /**
     * Cortex-mode flag for the current turn — {@code true} when a
     * Cortex-view client is currently bound to this session (the
     * client-tool registry contains tools labelled {@code "cortex"}).
     * Engines render a {@code {% if cortexMode %}} block in their
     * system prompt to tell the LLM the {@code cortex_*} tools are
     * available and which document is bound.
     *
     * <p>Per-turn — never persisted on the process. Default
     * {@code false} when the setter is not called. See
     * {@link de.mhus.vance.brain.tools.client.CortexPromptResolver}.
     */
    public PromptContextBuilder cortexMode(boolean cortexMode) {
        map.put("cortexMode", cortexMode);
        return this;
    }

    /**
     * Path of the document the Cortex chat is currently bound to —
     * exposed to templates as {@code {{ cortexBoundDocPath }}}. Only
     * meaningful when {@link #cortexMode(boolean)} is {@code true};
     * {@code null} when Cortex is open without a binding (the agent
     * has tools but no target yet).
     */
    public PromptContextBuilder cortexBoundDocPath(@Nullable String path) {
        if (path != null && !path.isBlank()) map.put("cortexBoundDocPath", path);
        return this;
    }

    /**
     * Mime-type of the bound Cortex document — companion to
     * {@link #cortexBoundDocPath(String)}. Lets the template tell the
     * agent up front when the bound document is a binary type
     * (image/PDF) so it doesn't waste a call on {@code doc_read}
     * just to discover the text tools won't apply.
     */
    public PromptContextBuilder cortexBoundDocMime(@Nullable String mime) {
        if (mime != null && !mime.isBlank()) map.put("cortexBoundDocMime", mime);
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

    /**
     * Pre-rendered Markdown block holding all addon-supplied prompt
     * fragments that apply to the current engine — exposed to engine
     * default and recipe override templates as {@code {{ addonSections }}}.
     * Normally set by {@link de.mhus.vance.brain.thinkengine.SystemPrompts#compose}
     * from {@link AddonPromptFragmentRegistry} output; engines whose
     * render path bypasses {@code SystemPrompts.compose} may set the
     * value here directly.
     *
     * <p>When the template references the variable it controls the
     * position; otherwise {@code SystemPrompts.compose} auto-appends the
     * block at the end of the rendered engine default. See
     * {@code specification/prompts-and-manuals.md} for the addon-fragment
     * convention.
     */
    public PromptContextBuilder addonSections(@Nullable String addonSections) {
        if (addonSections != null) map.put("addonSections", addonSections);
        return this;
    }

    /**
     * Exposes one boolean per workspace RootDir type present in the
     * current project, keyed as {@code has_<type>_rootdir}. Empty or
     * {@code null} input leaves all flags unset (lenient mode renders
     * them as empty string in {@code {% if has_*_rootdir %}} — which is
     * falsy in Pebble).
     *
     * <p>Example: a project with one {@code python} and two {@code git}
     * RootDirs ⇒ {@code has_python_rootdir=true, has_git_rootdir=true}.
     * Templates use it with {@code {% if has_python_rootdir %} … {% endif %}}
     * to gate type-specific tool hints without per-recipe configuration.
     *
     * <p>Type names are lower-cased and filtered to {@code [a-z0-9_]} so
     * a malformed descriptor can't inject arbitrary Pebble variables.
     */
    public PromptContextBuilder withRootDirTypes(@Nullable Set<String> types) {
        if (types == null || types.isEmpty()) return this;
        for (String raw : types) {
            if (raw == null || raw.isBlank()) continue;
            String normalised = raw.toLowerCase(Locale.ROOT);
            if (!SAFE_TYPE.matcher(normalised).matches()) continue;
            map.put("has_" + normalised + "_rootdir", Boolean.TRUE);
        }
        return this;
    }

    private static final Pattern SAFE_TYPE = Pattern.compile("[a-z0-9_]+");

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

    /**
     * Set a single top-level template variable. Engines call this for
     * one-off context values that aren't covered by the dedicated
     * setters above (e.g. Zaphod's {@code rounds} / {@code consensusReached}
     * for the synthesizer prompt). Overwrites if the key already
     * exists.
     */
    public PromptContextBuilder var(String key, @Nullable Object value) {
        map.put(key, value);
        return this;
    }

    public Map<String, Object> build() {
        return Map.copyOf(map);
    }

    /**
     * Read-only accessor used by {@link de.mhus.vance.brain.thinkengine.SystemPromptComposer}
     * to pull the {@code engine} name (and similar already-set values)
     * out of an in-progress builder without forcing the caller to pass
     * the engine name twice. Returns {@code null} when the key is
     * unset. Not part of the template-author contract — engines and
     * the composer are the only intended callers.
     */
    public @Nullable Object peek(String key) {
        return map.get(key);
    }
}
