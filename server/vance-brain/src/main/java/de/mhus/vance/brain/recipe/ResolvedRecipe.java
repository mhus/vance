package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one recipe — what the spawner uses after
 * {@link RecipeResolver#resolve} but <em>before</em> caller-supplied
 * params have been merged in. Carries the parsed YAML fields plus the
 * cascade source attribution.
 */
public record ResolvedRecipe(
        String name,
        String description,
        String engine,
        Map<String, Object> params,
        @Nullable String promptPrefix,
        PromptMode promptMode,
        @Nullable String dataRelayCorrection,
        List<String> allowedToolsAdd,
        List<String> allowedToolsRemove,
        /**
         * Recipe-base demotion list: tools listed here are moved to the
         * deferred bucket (LLM sees them only via the discovery block,
         * activated through {@code tool_description}). Profile and per-mode
         * overlays can promote individual entries back to primary via
         * {@code allowedToolsAdd}. See {@code planning/tool-schema-deferral.md} §4 / §14.
         */
        List<String> allowedToolsDefer,
        /**
         * Budget-priority hint: hold these tools in the manifest when the
         * endpoint's {@code tools}-array cap forces a cut ("important").
         * No visibility effect — a tool listed here still needs to be in
         * the allow-set to be primary at all, and without a cap the list
         * does nothing. Unioned across the profile/mode cascade rather
         * than resolved first-hit-wins, because ranking layers cannot
         * contradict each other. See {@code planning/tool-surface-budget.md}.
         */
        List<String> allowedToolsKeep,
        /**
         * Budget-priority hint: give these up first ("less important").
         * Same semantics as {@link #allowedToolsKeep}, opposite direction;
         * {@code keep} wins if a tool appears in both.
         */
        List<String> allowedToolsDropFirst,
        /**
         * Engine-mode overlay at the recipe-base level. Used when the
         * recipe doesn't have profile-specific mode blocks but still
         * wants per-mode tool restrictions. The cascade in
         * {@link RecipeResolver#toolFilterFor} consults this last,
         * after profile-specific mode blocks.
         */
        Map<String, RecipeModeBlock> modes,
        Map<String, ProfileBlock> profiles,
        /**
         * Skill names that are sticky-activated on the spawned process
         * with {@code fromRecipe=true}. Empty list means "no skills
         * pinned by this recipe".
         */
        List<String> defaultActiveSkills,
        /**
         * Whitelist of skill names that may ever be active on the spawned
         * process — covers trigger-matched, default-active, and {@code /skill}
         * activations. {@code null} means "no restriction" (heutiges
         * Default-Verhalten); an empty list means complete lock-down.
         */
        @Nullable List<String> allowedSkills,
        /**
         * Trigger keywords parsed from the YAML {@code triggers.keywords}
         * block. Used by {@link de.mhus.vance.brain.delegate.RecipeSelectorService}
         * for the deterministic pre-check that fires before any LLM call.
         * Empty list means "no triggers" — this recipe is structurally
         * invoked (eddie, arthur, ford, default) or only by explicit name.
         * Entries are normalised to lower-case at parse time so matching
         * is case-insensitive without per-call work.
         */
        List<String> triggerKeywords,
        boolean locked,
        /**
         * Marks the recipe as an internal config profile, e.g. for
         * {@code LightLlmService} consumers (discovery, title-gen, …).
         * The {@code RecipeSelectorService} skips internal recipes when
         * listing candidates for the LLM-driven {@code DELEGATE}
         * selector — they can only be loaded by explicit name via the
         * service that owns them. Default {@code false}.
         */
        boolean internal,
        /**
         * Opt-in flag: when {@code true}, the recipe is exposed by the
         * tenant-facing "listed recipes" endpoint that drives the Web-UI
         * recipe picker (and any future client recipe pickers). Defaults
         * to {@code false} so that helper/config recipes
         * ({@code internal: true} or otherwise infrastructure-only)
         * stay out of the user-facing list unless their author explicitly
         * opts in. See {@code specification/recipes.md}.
         */
        boolean listed,
        /**
         * Opt-in flag: may this recipe be invoked **from a web client**, over
         * the generic light-LLM route.
         *
         * <p>Not "may an app call it" — an app *is* a web client, and every web
         * client can reach the same route, so a per-app permission would be a
         * fiction. The question that can actually be answered is whether this
         * recipe is meant to be triggered from a browser at all, and only the
         * person who wrote the recipe can answer it.
         *
         * <p>Independent of {@code internal}: the light-LLM service requires
         * {@code internal: true} for every recipe it runs (a config profile, not
         * a spawnable worker), so this is a **second** gate on top, not an
         * alternative to it. A recipe reachable from the web is therefore
         * `internal: true` *and* `web: true`.
         *
         * <p>Purpose-built endpoints keep their own contract — {@code follow-up}
         * is called from the Web-UI through its own route and needs no flag. The
         * flag governs the route that takes a recipe *name* from the caller.
         *
         * <p>Default {@code false}. Anything else would make every existing
         * config profile — discovery, title generation, fook triage — callable
         * with an arbitrary prompt by anyone who can open the app.
         */
        boolean web,
        /**
         * Optional human-readable display name for clients that surface
         * the recipe to the user (Web-UI recipe picker, future mobile
         * UIs). When {@code null}, the {@link #name} is used as fallback.
         */
        @Nullable String title,
        List<String> tags,
        /**
         * Completion guards (recipe {@code guard:} block). Engine-agnostic:
         * any engine that calls {@code CompletionGuardService.evaluate} at
         * its yield point honours these. Empty list = no guards. See
         * {@code planning/completion-guard.md}.
         */
        List<GuardConfig> guards,
        /**
         * Which tenants this recipe is for. Empty means every tenant —
         * which is what every recipe written before this field existed
         * says, and the only default that keeps them alive.
         *
         * <p>Exists because a bundled recipe lives on the classpath, and
         * the classpath layer of the lookup cascade is tenant-agnostic:
         * whatever an addon ships is found by <em>every</em> tenant. For a
         * recipe that starts an agent with credentials of its own, that is
         * the wrong reach.
         *
         * <p><b>Enforced in {@code RecipeLoader.load}, not at a display
         * site</b> — see there for why that distinction is the whole
         * point.
         */
        List<String> tenants,
        RecipeSource source) {

    /**
     * Whether this recipe may be used by {@code tenantId}.
     *
     * <p>Case- and whitespace-tolerant on the configured side, because the
     * value is hand-written YAML; the asked-for id comes from the system
     * and is taken as it is.
     */
    public boolean appliesTo(@Nullable String tenantId) {
        if (tenants == null || tenants.isEmpty()) return true;
        if (tenantId == null) return false;
        for (String allowed : tenants) {
            if (allowed != null && allowed.trim().equalsIgnoreCase(tenantId)) return true;
        }
        return false;
    }

    /**
     * Backward-compatible constructor for call sites that predate the
     * tool-surface budget — no {@code allowedToolsKeep} /
     * {@code allowedToolsDropFirst}.
     *
     * <p>Also predates {@code tenants}, and passes the empty list: a call
     * site that does not know about the field means a recipe without one,
     * and that is "every tenant".
     */
    public ResolvedRecipe(
            String name,
            String description,
            String engine,
            Map<String, Object> params,
            @Nullable String promptPrefix,
            PromptMode promptMode,
            @Nullable String dataRelayCorrection,
            List<String> allowedToolsAdd,
            List<String> allowedToolsRemove,
            List<String> allowedToolsDefer,
            Map<String, RecipeModeBlock> modes,
            Map<String, ProfileBlock> profiles,
            List<String> defaultActiveSkills,
            @Nullable List<String> allowedSkills,
            List<String> triggerKeywords,
            boolean locked,
            boolean internal,
            boolean listed,
            @Nullable String title,
            List<String> tags,
            List<GuardConfig> guards,
            RecipeSource source) {
        this(name, description, engine, params, promptPrefix, promptMode,
                dataRelayCorrection, allowedToolsAdd, allowedToolsRemove,
                allowedToolsDefer, List.of(), List.of(), modes, profiles,
                defaultActiveSkills, allowedSkills,
                triggerKeywords, locked, internal, listed, false, title, tags,
                guards, List.of(), source);
    }
}
