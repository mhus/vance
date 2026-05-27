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
 * Picks a project recipe for a free-text task description. Trigger-
 * gated: a deterministic pre-check (recipe-name word-boundary + trigger-
 * keyword substring) runs first. The LLM call only fires when multiple
 * recipes match the same trigger and need disambiguation. Without any
 * trigger the selector returns {@code NONE} immediately — caller falls
 * back to its default recipe (typically {@code default} → ford).
 *
 * <p>See {@code specification/recipe-routing.md} for the full design.
 *
 * <h2>Pre-check stages</h2>
 * <ol>
 *   <li><b>Recipe-name word-boundary match.</b> Any recipe name that
 *       appears as a word in the goal text is a direct match — no LLM,
 *       no ambiguity. Longest-match wins when two recipe names overlap
 *       (e.g. {@code analyze} vs {@code deep-analyze}).</li>
 *   <li><b>Trigger-keyword substring match.</b> Recipes declare phrases
 *       via {@code triggers.keywords:} in their YAML. Substring match
 *       on the lower-cased goal. Multiple matches → only-matched
 *       candidates feed the LLM for disambiguation; single match →
 *       deterministic.</li>
 *   <li><b>No trigger.</b> Return {@code NONE} with a rationale.
 *       The caller spawns its default recipe (no Slart fallback —
 *       see {@code routing.fallback.recipe} setting).</li>
 * </ol>
 *
 * <h2>Why deterministic comes first</h2>
 *
 * Magical LLM-routing on every empty-recipe spawn was the source of
 * the worst routing failures we saw in field testing: the selector
 * picked specialist engines (Marvin, Vogon) for trivial tasks, or
 * the opposite. Forcing the user to <em>opt in</em> by naming the
 * engine / recipe / category keyword turns the selector into a clear
 * escalation gate.
 *
 * <h2>LLM disambiguation</h2>
 *
 * When the deterministic pre-check finds multiple candidates, the
 * tie-break runs through {@link LightLlmService} using the bundled
 * {@code recipe-selector} recipe (config profile,
 * {@code internal: true}). Tenants override the recipe to bias the
 * disambiguation prompt or swap the model — no Java change required.
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

        // Stage 1: deterministic recipe-name match.
        ResolvedRecipe byName = matchByRecipeName(inventory, lower);
        if (byName != null) {
            log.debug("RecipeSelector: deterministic recipe-name match recipe='{}' engine='{}'",
                    byName.name(), byName.engine());
            return Result.match(byName.name(), byName.engine(),
                    "Recipe name '" + byName.name()
                            + "' detected in goal (deterministic, no LLM call).");
        }

        // Stage 2: trigger-keyword pre-filter.
        List<ResolvedRecipe> triggered = matchByTriggerKeywords(inventory, lower);
        if (triggered.isEmpty()) {
            return Result.noneWithoutTrigger(
                    "no trigger detected in goal — caller falls back to "
                            + "default recipe (no LLM call made).");
        }
        if (triggered.size() == 1) {
            ResolvedRecipe r = triggered.get(0);
            log.debug("RecipeSelector: single trigger-keyword match recipe='{}' engine='{}'",
                    r.name(), r.engine());
            return Result.match(r.name(), r.engine(),
                    "Trigger keyword in recipe '" + r.name()
                            + "' matched the goal (deterministic, no LLM call).");
        }

        // Stage 3: multiple candidates → ask the LLM for disambiguation.
        log.debug("RecipeSelector: {} candidates triggered, running LLM disambiguation",
                triggered.size());
        return runLlmDisambiguation(caller, triggered, taskDescription);
    }

    // ──────────────────── deterministic matchers ────────────────────

    /**
     * Walks the full inventory and returns the recipe whose name
     * appears as a stand-alone word in the goal text. Word boundary
     * counts {@code a-z 0-9 _ -} as word characters so hyphenated
     * names like {@code quick-lookup} stay intact. Longest match
     * wins to avoid {@code analyze} stealing from {@code deep-analyze}.
     */
    private static @Nullable ResolvedRecipe matchByRecipeName(
            List<ResolvedRecipe> inventory, String lowerGoal) {
        ResolvedRecipe best = null;
        int bestLen = 0;
        for (ResolvedRecipe r : inventory) {
            String n = r.name().toLowerCase(Locale.ROOT);
            if (n.isEmpty() || n.length() <= bestLen) continue;
            if (containsAsWord(lowerGoal, n)) {
                best = r;
                bestLen = n.length();
            }
        }
        return best;
    }

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

    /**
     * Word-boundary {@link String#contains}. We hand-roll instead of
     * using a regex because we walk the inventory for every spawn
     * and want the allocation profile flat.
     */
    static boolean containsAsWord(String haystack, String needle) {
        if (needle.isEmpty() || needle.length() > haystack.length()) return false;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            char before = idx == 0 ? ' ' : haystack.charAt(idx - 1);
            int after = idx + needle.length();
            char afterC = after >= haystack.length() ? ' ' : haystack.charAt(after);
            if (!isWordChar(before) && !isWordChar(afterC)) return true;
            idx++;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
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
     * Runs the LightLlm-backed disambiguation only over the trigger-
     * matched candidates. The {@code recipe-selector} recipe handles
     * the system prompt, schema-retry budget, and model alias; we
     * supply only the candidates and the task description as Pebble
     * vars and cross-check the returned name against the candidate
     * list afterwards.
     */
    private Result runLlmDisambiguation(
            ThinkProcessDocument caller,
            List<ResolvedRecipe> candidates,
            String taskDescription) {
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
            return Result.noneAfterTrigger(
                    "LLM could not produce a valid reply within "
                            + e.getAttempts() + " attempts");
        } catch (LightLlmException e) {
            log.warn("RecipeSelector: LLM call failed: {}", e.toString());
            return Result.noneAfterTrigger("LLM call failed: " + e.getMessage());
        }

        return parseResult(raw, candidates);
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
     * All NONE / failure paths here come from the LLM-disambiguation
     * stage, which only runs when the trigger pre-check matched ≥1
     * candidate. They are therefore {@link Result#noneAfterTrigger}
     * cases — caller should consult {@code routing.fallback.recipe},
     * not the default recipe.
     */
    private Result parseResult(Map<String, Object> raw, List<ResolvedRecipe> candidates) {
        Object decisionRaw = raw.get(FIELD_DECISION);
        if (!(decisionRaw instanceof String decision)) {
            return Result.noneAfterTrigger("LLM reply missing 'decision' field");
        }
        String rationale = raw.get(FIELD_RATIONALE) instanceof String s ? s : "";
        if ("NONE".equalsIgnoreCase(decision)) {
            return Result.noneAfterTrigger(orFallback(rationale,
                    "LLM returned NONE without rationale"));
        }
        if (!"MATCH".equalsIgnoreCase(decision)) {
            return Result.noneAfterTrigger(
                    "LLM returned unrecognised decision: " + decision);
        }
        Object pickedRaw = raw.get(FIELD_RECIPE);
        if (!(pickedRaw instanceof String picked) || picked.isBlank()) {
            return Result.noneAfterTrigger("LLM returned MATCH without a recipe name");
        }
        String pickedTrim = picked.trim();
        for (ResolvedRecipe r : candidates) {
            if (r.name().equals(pickedTrim)) {
                return Result.match(pickedTrim, r.engine(), orFallback(rationale, ""));
            }
        }
        return Result.noneAfterTrigger(
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
