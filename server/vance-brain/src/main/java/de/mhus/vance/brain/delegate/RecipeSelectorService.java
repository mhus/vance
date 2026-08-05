package de.mhus.vance.brain.delegate;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Picks a project recipe for a free-text task description. This is the
 * <em>no-preset</em> path: the delegating engine (e.g. Arthur) may name
 * a recipe explicitly via {@code preset}, in which case the selector is
 * never consulted. When it doesn't, this service decides.
 *
 * <p>See {@code specification/recipe-routing.md} for the full design.
 *
 * <h2>Stages</h2>
 * <ol>
 *   <li><b>Trigger-keyword fast-path.</b> Recipes declare curated intent
 *       phrases via {@code triggers.keywords:} in their YAML (substring
 *       match on the lower-cased goal). A <em>single</em> hit routes
 *       deterministically — no LLM call. Curated phrases are collision-
 *       safe: {@code "run python"} matches a real python intent but not
 *       {@code "a diagram about python"}.</li>
 *   <li><b>Semantic LLM routing.</b> Zero or multiple trigger hits →
 *       one {@link LightLlmService} call over the <em>full</em> routable
 *       inventory (names + descriptions), using the bundled
 *       {@code recipe-selector} recipe. It returns a concrete recipe or
 *       {@code NONE}. A NONE / failure verdict falls back to
 *       {@code default} (no trigger fired) or {@code routing.fallback.recipe}
 *       (a trigger fired), tracked by {@code triggerObserved}.</li>
 * </ol>
 *
 * <h2>Why a blind recipe-name match is NOT used</h2>
 *
 * An earlier version added a deterministic stage that matched any recipe
 * name appearing as a word in the goal. It could not tell a routing
 * intent ("do python work") from subject content ("a mindmap ABOUT
 * python"): the content word {@code python} routed to the {@code python}
 * recipe, whose worker then lacked {@code doc_write}. Intent-vs-content
 * is a semantic distinction a string matcher cannot make — so it now
 * belongs to the model ({@code preset}), to curated trigger phrases, or
 * to the semantic LLM stage. The LLM call is deliberately accepted here
 * for routing correctness; it is gated behind the preset and single-
 * trigger fast-paths, and produces structured output with a {@code NONE}
 * safety net, so it is not the "magical routing on every spawn" that
 * earlier field-testing found unreliable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeSelectorService {

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "recipe-selector";

    /** Reply field names — match the schema enforced below. */
    static final String FIELD_DECISION = "decision";
    static final String FIELD_RECIPE = "recipe";
    static final String FIELD_RATIONALE = "rationale";

    /** Closed-vocabulary schema for the disambiguation reply. */
    static final Map<String, Object> SELECTOR_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    FIELD_DECISION, Map.of(
                            "type", "string",
                            "enum", List.of("MATCH", "NONE"),
                            "description", "MATCH when one candidate fits; "
                                    + "NONE when none does."),
                    // recipe intentionally has no `type` constraint —
                    // JsonSchemaLight does not support string-or-null
                    // unions, and a post-call candidate cross-check is
                    // authoritative anyway.
                    FIELD_RECIPE, Map.of(
                            "description", "Recipe name verbatim from the "
                                    + "candidate list on MATCH; null on NONE."),
                    FIELD_RATIONALE, Map.of(
                            "type", "string",
                            "description", "1-2 sentences explaining the "
                                    + "choice; surfaced to caller logs.")),
            "required", List.of(FIELD_DECISION, FIELD_RATIONALE));

    private final RecipeLoader recipeLoader;
    private final LightLlmService lightLlm;

    /**
     * Runs the selector. Returns a {@link Result} describing what to
     * do — never throws on a bad LLM response (returns
     * {@link Result#noneAfterTrigger(String)} with a diagnostic instead)
     * so the caller can decide how to fall back.
     */
    public Result select(ThinkProcessDocument caller, String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return Result.noneWithoutTrigger("empty task description");
        }
        List<ResolvedRecipe> inventory = listRecipesForRouting(caller);
        if (inventory.isEmpty()) {
            return Result.noneWithoutTrigger(
                    "no recipes available in tenant/project — "
                            + "caller falls back to default recipe");
        }

        String lower = taskDescription.toLowerCase(Locale.ROOT);

        // Stage 1: trigger-keyword fast-path. Curated intent phrases
        // (a recipe's triggers.keywords) are collision-safe — "run python"
        // matches a genuine python intent but not "a diagram about python".
        // A single hit routes deterministically with no LLM call.
        List<ResolvedRecipe> triggered = matchByTriggerKeywords(inventory, lower);
        if (triggered.size() == 1) {
            ResolvedRecipe r = triggered.get(0);
            log.debug("RecipeSelector: single trigger-keyword match recipe='{}' engine='{}'",
                    r.name(), r.engine());
            return Result.match(r.name(), r.engine(),
                    "Trigger keyword in recipe '" + r.name()
                            + "' matched the goal (deterministic, no LLM call).");
        }

        // Stage 2: semantic routing over the FULL inventory. No cheap
        // deterministic signal resolved the recipe (the delegating engine
        // set no explicit preset, and zero or multiple trigger keywords
        // fired), so the LLM reads the goal against the whole recipe
        // catalogue and decides — a concrete recipe or NONE.
        //
        // This replaces the former blind recipe-NAME word-match, which
        // could not tell a routing intent ("do python work") from subject
        // content ("a mindmap ABOUT python") and wrongly routed the latter
        // to the python recipe (whose worker then lacked doc_write). We
        // deliberately accept one LightLlm call here for a core-routing
        // correctness win: it only runs when neither an explicit preset nor
        // a single trigger already resolved the recipe. The triggerObserved
        // flag is preserved so a NONE verdict still falls back correctly —
        // to `default` when no trigger fired, to routing.fallback.recipe
        // when one did.
        boolean triggerObserved = !triggered.isEmpty();
        log.debug("RecipeSelector: no single trigger match ({} hit(s)) — "
                        + "semantic LLM routing over {} recipe(s)",
                triggered.size(), inventory.size());
        return runLlmDisambiguation(caller, inventory, taskDescription, triggerObserved);
    }

    // ──────────────────── trigger pre-filter ────────────────────

    /**
     * Returns every recipe whose {@code triggerKeywords} contains a
     * substring found in {@code lowerGoal}. Trigger keywords are
     * already lower-cased at parse time, so the comparison is a plain
     * {@link String#contains}. Order matches the inventory order,
     * which itself reflects the cascade (project → tenant → bundled).
     */
    private static List<ResolvedRecipe> matchByTriggerKeywords(
            List<ResolvedRecipe> inventory, String lowerGoal) {
        List<ResolvedRecipe> hits = new ArrayList<>();
        for (ResolvedRecipe r : inventory) {
            List<String> kws = r.triggerKeywords();
            if (kws == null || kws.isEmpty()) continue;
            for (String kw : kws) {
                if (lowerGoal.contains(kw)) {
                    hits.add(r);
                    break;
                }
            }
        }
        return hits;
    }

    // ──────────────────── inventory ────────────────────

    /**
     * Returns the recipe inventory the routing layer should consider.
     * Excludes:
     * <ul>
     *   <li>{@code _slart/*} — Slart's persisted past outputs are
     *       not human-curated recipes,</li>
     *   <li>{@code _*} — system-internal documents.</li>
     * </ul>
     * Engine-default tagged recipes (marvin, hactar, zaphod, …) ARE
     * included — they are matchable via their own name or via their
     * declared trigger keywords. The {@code engine-default} tag was
     * historically used to hide them from the LLM-driven inventory
     * dump; the new deterministic pre-check needs them present.
     */
    private List<ResolvedRecipe> listRecipesForRouting(ThinkProcessDocument caller) {
        try {
            List<ResolvedRecipe> all = recipeLoader.listAll(
                    caller.getTenantId(), caller.getProjectId());
            List<ResolvedRecipe> visible = new ArrayList<>(all.size());
            for (ResolvedRecipe r : all) {
                if (r.name().startsWith("_slart/")) continue;
                if (r.name().startsWith("_")) continue;
                // Internal config-profile recipes (e.g. how-do-i for the
                // DiscoveryService via LightLlmService) are never offered
                // to the DELEGATE selector — they are only loaded by name
                // through the service that owns them.
                if (r.internal()) continue;
                visible.add(r);
            }
            return visible;
        } catch (RuntimeException e) {
            log.warn("RecipeSelector: failed listing recipes for tenant='{}' project='{}': {}",
                    caller.getTenantId(), caller.getProjectId(), e.toString());
            return List.of();
        }
    }

    // ──────────────────── LLM disambiguation ────────────────────

    /**
     * Runs the LightLlm-backed semantic router over {@code candidates}
     * (the full routable inventory). The {@code recipe-selector} recipe
     * handles the system prompt, schema-retry budget, and model alias; we
     * supply the candidates and the task description as Pebble vars and
     * cross-check the returned name against the candidate list afterwards.
     *
     * @param triggerObserved whether any trigger keyword fired for this
     *        goal. Threaded into every NONE / failure verdict so the
     *        caller falls back to {@code default} (no trigger) vs
     *        {@code routing.fallback.recipe} (trigger seen) correctly.
     */
    private Result runLlmDisambiguation(
            ThinkProcessDocument caller,
            List<ResolvedRecipe> candidates,
            String taskDescription,
            boolean triggerObserved) {
        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt(taskDescription)
                    .pebbleVars(Map.of(
                            "candidates", renderCandidates(candidates),
                            "task", taskDescription))
                    .schema(SELECTOR_SCHEMA)
                    .tenantId(caller.getTenantId())
                    .projectId(caller.getProjectId())
                    .processId(caller.getId())
                    .build());
        } catch (SchemaValidationException e) {
            log.warn("RecipeSelector: schema budget exhausted attempts={} last='{}'",
                    e.getAttempts(), e.getLastError());
            return none(triggerObserved,
                    "LLM could not produce a valid reply within "
                            + e.getAttempts() + " attempts");
        } catch (LightLlmException e) {
            log.warn("RecipeSelector: LLM call failed: {}", e.toString());
            return none(triggerObserved, "LLM call failed: " + e.getMessage());
        }

        return parseResult(raw, candidates, triggerObserved);
    }

    /** NONE verdict routed to the fallback bucket the caller expects:
     *  {@code default} when no trigger fired, {@code routing.fallback.recipe}
     *  when one did. */
    private static Result none(boolean triggerObserved, String rationale) {
        return triggerObserved
                ? Result.noneAfterTrigger(rationale)
                : Result.noneWithoutTrigger(rationale);
    }

    /**
     * Flattens candidates to plain maps for the Pebble
     * {@code {% for c in candidates %}} loop. Stable inventory order
     * keeps the prompt cache warm across selectors against the same
     * project snapshot.
     */
    static List<Map<String, String>> renderCandidates(List<ResolvedRecipe> candidates) {
        List<Map<String, String>> out = new ArrayList<>(candidates.size());
        for (ResolvedRecipe r : candidates) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("engine", r.engine() == null ? "" : r.engine());
            String desc = r.description();
            if (desc != null && !desc.isBlank()) {
                m.put("description", desc.trim().replaceAll("\\s+", " "));
            }
            out.add(m);
        }
        return out;
    }

    // ──────────────────── response parsing ────────────────────

    /**
     * Parses the semantic router's reply. A NONE / failure verdict is
     * routed to the caller's expected fallback via {@link #none} using
     * {@code triggerObserved}: {@code default} when no trigger fired,
     * {@code routing.fallback.recipe} when one did.
     */
    private Result parseResult(Map<String, Object> raw,
            List<ResolvedRecipe> candidates, boolean triggerObserved) {
        Object decisionRaw = raw.get(FIELD_DECISION);
        if (!(decisionRaw instanceof String decision)) {
            return none(triggerObserved, "LLM reply missing 'decision' field");
        }
        String rationale = raw.get(FIELD_RATIONALE) instanceof String s ? s : "";
        if ("NONE".equalsIgnoreCase(decision)) {
            return none(triggerObserved, orFallback(rationale,
                    "LLM returned NONE without rationale"));
        }
        if (!"MATCH".equalsIgnoreCase(decision)) {
            return none(triggerObserved,
                    "LLM returned unrecognised decision: " + decision);
        }
        Object pickedRaw = raw.get(FIELD_RECIPE);
        if (!(pickedRaw instanceof String picked) || picked.isBlank()) {
            return none(triggerObserved, "LLM returned MATCH without a recipe name");
        }
        String pickedTrim = picked.trim();
        for (ResolvedRecipe r : candidates) {
            if (r.name().equals(pickedTrim)) {
                return Result.match(pickedTrim, r.engine(), orFallback(rationale, ""));
            }
        }
        return none(triggerObserved,
                "LLM returned unknown recipe '" + pickedTrim + "' — not in candidate list");
    }

    private static String orFallback(@Nullable String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    // ──────────────────── result types ────────────────────

    /** What the selector decided. {@code engineName} is a courtesy
     *  echo so callers can log / audit without a second lookup.
     *  {@code triggerObserved} tells the caller whether the user goal
     *  contained a trigger (recipe-name / engine-name / declared
     *  keyword) — the {@code process_create} fallback chain treats
     *  {@code NONE + triggerObserved=true} (trigger seen, no match)
     *  differently from {@code NONE + triggerObserved=false} (no
     *  trigger at all → use the {@code default} recipe → ford). */
    public record Result(
            Decision decision,
            @Nullable String recipeName,
            @Nullable String engineName,
            boolean triggerObserved,
            String rationale) {

        public enum Decision { MATCH, NONE }

        public static Result match(String recipe, String engine, String rationale) {
            return new Result(Decision.MATCH, recipe, engine, true, rationale);
        }

        /** No trigger detected in the goal — caller should fall
         *  through to the standard default recipe ({@code default}
         *  → ford). The configurable fallback recipe is reserved
         *  for the trigger-observed-but-no-match case. */
        public static Result noneWithoutTrigger(String rationale) {
            return new Result(Decision.NONE, null, null, false, rationale);
        }

        /** Trigger detected but no candidate matched (multi-candidate
         *  LLM disambiguation returned NONE, or the LLM call failed).
         *  Caller should consult {@code routing.fallback.recipe}. */
        public static Result noneAfterTrigger(String rationale) {
            return new Result(Decision.NONE, null, null, true, rationale);
        }
    }
}
