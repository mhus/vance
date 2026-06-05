package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.prompt.AddonPromptFragmentRegistry;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single entry point for engines that need to render a system prompt
 * with addon fragments merged in. Bundles
 * {@link PromptTemplateRenderer} and {@link AddonPromptFragmentRegistry}
 * so each engine injects one bean instead of two and writes one line
 * instead of five.
 *
 * <p>Two render paths are supported:
 * <ul>
 *   <li>{@link #compose(ThinkProcessDocument, String, PromptContextBuilder)}
 *       — standard path for engines that drive one full LLM call per
 *       turn (Arthur, Eddie, Ford). Renders engine default + recipe
 *       override + profile-append + addons via {@link SystemPrompts}.
 *   </li>
 *   <li>{@link #withAddons(String, PromptContextBuilder)} +
 *       {@link #render(String, Map)} — for engines with custom render
 *       pipelines (Zaphod synthesis, Marvin tree-workers, Slart phases)
 *       that build the prompt themselves and just want the addon block
 *       available as a Pebble variable.</li>
 * </ul>
 *
 * <p>The composer reads the engine name out of the builder via
 * {@link PromptContextBuilder#peek} — callers must therefore set
 * {@code .engine(NAME)} before handing the builder in.
 *
 * <p>Stateless service. Thread-safe (both delegates are thread-safe).
 */
@Service
@RequiredArgsConstructor
public class SystemPromptComposer {

    private final PromptTemplateRenderer renderer;
    private final AddonPromptFragmentRegistry addonRegistry;

    /**
     * Standard render path: assembles addon block, merges it into the
     * builder context, then runs {@link SystemPrompts#compose}.
     *
     * <p>The caller hands in a builder pre-populated with all per-turn
     * variables (tier, model, provider, mode, profile, voiceMode,
     * recipe, engine, lang, params, …). The composer then:
     * <ol>
     *   <li>Snapshots the context once for addon rendering.</li>
     *   <li>Renders every fragment registered for the engine and joins
     *       them (deterministic alphabetical order).</li>
     *   <li>Stores the joined block in the builder via
     *       {@link PromptContextBuilder#addonSections} so the engine
     *       default template can reference {@code {{ addonSections }}}.</li>
     *   <li>Calls {@link SystemPrompts#compose} which handles
     *       engine-default + recipe-override + profile-append blending
     *       and the addon auto-append fallback.</li>
     * </ol>
     */
    public String compose(
            ThinkProcessDocument process,
            String engineDefault,
            PromptContextBuilder ctxBuilder) {
        withAddons(engineNameFrom(ctxBuilder), ctxBuilder);
        return SystemPrompts.compose(
                process, engineDefault, renderer, ctxBuilder.build());
    }

    /**
     * Renders the addon block for {@code engineName} against the
     * builder's current state and stores the result back in the builder
     * under the {@code addonSections} key. Returns the same builder for
     * chaining.
     *
     * <p>Use this when the engine drives its own render pipeline and
     * just needs the addon block available as a Pebble variable on the
     * final context (Zaphod synthesis, Marvin worker prompts, Slart
     * phase prompts).
     */
    public PromptContextBuilder withAddons(
            String engineName, PromptContextBuilder ctxBuilder) {
        String rendered = addonRegistry.renderAndJoin(
                engineName, ctxBuilder.build(), renderer);
        return ctxBuilder.addonSections(rendered);
    }

    /**
     * Map-flavoured variant of {@link #withAddons(String, PromptContextBuilder)}
     * for engines (Hactar phases, …) whose render-context is not a clean
     * builder chain but a custom {@link Map} populated with engine-
     * specific variables (goal, toolInventory, …). Returns the joined
     * addon block as a {@link String} so the caller can put it into the
     * map under whatever key the template expects (usually
     * {@code "addonSections"}).
     */
    public String renderAddons(String engineName, Map<String, Object> ctx) {
        return addonRegistry.renderAndJoin(engineName, ctx, renderer);
    }

    /**
     * Convenience wrapper around {@link PromptTemplateRenderer#render}
     * for engines that already injected the composer — saves a second
     * dependency on the raw renderer.
     */
    public @Nullable String render(@Nullable String template, Map<String, Object> ctx) {
        return renderer.render(template, ctx);
    }

    /**
     * Convenience wrapper around {@link PromptTemplateRenderer#compile}
     * for fail-fast validation at load time (recipe / architect / wizard
     * compile passes). Engines that already have the composer injected
     * call this instead of dragging a second renderer dependency in.
     */
    public void compile(@Nullable String template) {
        renderer.compile(template);
    }

    /** Direct access to the underlying renderer for the rare callers
     *  that need {@link PromptTemplateRenderer#compile} or other
     *  non-render entrypoints. Prefer {@link #render} or {@link #compose}
     *  for the standard paths. */
    public PromptTemplateRenderer renderer() {
        return renderer;
    }

    private static String engineNameFrom(PromptContextBuilder ctxBuilder) {
        Object raw = ctxBuilder.peek("engine");
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalStateException(
                "SystemPromptComposer requires .engine(NAME) on the builder "
                        + "before compose/withAddons — addon-fragment lookup "
                        + "is keyed by engine name");
    }
}
