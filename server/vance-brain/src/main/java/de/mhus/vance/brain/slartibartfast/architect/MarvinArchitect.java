package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Marvin-recipe architect. Produces recipes with
 * {@code engine: marvin}, a non-blank top-level
 * {@code promptPrefix} (the PLAN-LLM instruction), and a
 * {@code params} block carrying the Marvin runtime constraints
 * (allowedSubTaskRecipes, recipesOnlyViaExpand,
 * allowedExpandDocumentRefPaths, disallowedTaskKinds,
 * defaultExecutionMode, maxPlanCorrections).
 *
 * <p>Status note: as documented in
 * {@code specification/slartibartfast-engine.md} §4, the
 * Marvin-recipe path ships skeleton-only today. The system prompt
 * and validators below are production-shaped — the open work is
 * promoting PROPOSING from placeholder to fully-driven Marvin
 * output. Carrying this code as its own bean isolates that work
 * from Vogon and Zaphod when it lands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinArchitect implements SchemaArchitect {

    public static final String RULE_MARVIN_RECIPE_SHAPE =
            "marvin-recipe-shape";
    public static final String RULE_MARVIN_PROMPT_PREFIX =
            "marvin-recipe-prompt-prefix-present";
    public static final String RULE_PROMPT_PREFIX_TEMPLATE_VALID =
            "recipe-prompt-prefix-pebble-template-valid";
    public static final String RULE_MARVIN_RECIPES_EXIST =
            "marvin-recipe-allowed-recipes-exist";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal, the subgoals, and the list of
            available sub-recipes, you produce a Marvin recipe.
            Marvin's PLAN validator enforces your constraints at
            runtime — if you omit them the runtime PLAN-LLM takes
            shortcuts and the pipeline does not run through.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML>",
                  "justifications": {
                    "params.allowedSubTaskRecipes": "<sg-id>",
                    "promptPrefix":                 "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            ── COMPLETENESS REQUIREMENT ──

            Your recipe MUST drive the user's request to a final
            deliverable, not just one stage of it. If the user
            asks for an essay, the recipe MUST run outline →
            chapter writing → aggregation (final consolidation).
            Producing only the outline phase, only chapters, or
            stopping before aggregation is a hard failure: the
            user gets no usable result and the validator will
            reject the recipe.

            When the available sub-recipes include outline-style,
            chapter-style, AND aggregator-style entries, you MUST
            wire all three into allowedSubTaskRecipes and the
            promptPrefix. Pick fewer phases ONLY when the user's
            request truly needs less (e.g. they explicitly asked
            for an outline only).

            ── MANDATORY CONSTRAINTS (set when applicable) ──

            These constraints are NOT optional when the inputs
            motivate them. Slartibartfast detects motivated
            constraints from the subgoals + the available sub-recipes:

            **allowedSubTaskRecipes** — REQUIRED whenever the
              subgoals map to concrete sub-recipes. Inspect the
              "Available sub-recipes" block in the user prompt. A
              subgoal that "writes plot/cast/outline" maps to an
              outline-style recipe; "writes chapters" to a
              chapter-style recipe; "consolidates/aggregates" to an
              aggregator-style recipe. List EXACTLY the recipes
              your plan phases will use.

            **recipesOnlyViaExpand** — REQUIRED when a subgoal
              iterates "per item in a document". List the recipes
              that may appear ONLY inside an EXPAND_FROM_DOC
              childTemplate (typical: chapter-loop for
              chapter-per-outline-item).

            **allowedExpandDocumentRefPaths** — REQUIRED when you
              set recipesOnlyViaExpand. List the document paths the
              EXPAND_FROM_DOC iterates over (e.g.
              "essay/outline.md").

            **disallowedTaskKinds** — Set [AGGREGATE] when your
              plan shape needs a WORKER aggregator (instead of
              Marvin's built-in AGGREGATE summary). Standard for
              pipelines with an aggregator-style recipe.

            **defaultExecutionMode: SEQUENTIAL** — Default when the
              plan phases build on each other (outline → chapters →
              aggregator). Use PARALLEL only when phases are
              independent.

            **maxPlanCorrections: 2** — Default. Omit only for
              extremely conservative use cases.

            ── promptPrefix CONTRACT (the runtime PLAN-LLM reads it) ──

            **CRITICAL — KIND-block parity rule:**
              The number of KIND blocks in your promptPrefix MUST
              equal the size of allowedSubTaskRecipes (and the
              number of children Marvin's PLAN should emit).
              EVERY recipe in allowedSubTaskRecipes MUST have
              exactly one KIND block referencing it (either as a
              direct WORKER taskSpec.recipe, or — for entries in
              recipesOnlyViaExpand — as the EXPAND_FROM_DOC
              childTemplate.recipe). If you list 3 recipes you MUST
              write 3 KIND blocks; the runtime LLM otherwise drops
              the trailing ones.

            **JSON skeleton rule:** Each KIND block MUST contain a
              concrete JSON skeleton for that child (literal
              taskKind / goal / taskSpec). Plain prose without a
              JSON sample lets the LLM omit the child.

            **Order rule:** The KIND blocks MUST appear in
              execution order (the order Marvin will use under
              SEQUENTIAL). The order also reflects the
              data-dependency chain (a phase that consumes
              `essay/outline.md` comes after the phase that
              produces it).

            **No manual fan-out:** "Iterate per item in a document"
              ALWAYS means EXPAND_FROM_DOC with documentRef +
              childTemplate. Never enumerate items by hand.

            ── YAML structure ──

                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - <recipe1-name>
                    - <recipe2-name>
                  recipesOnlyViaExpand:
                    - <chapter-loop-name>
                  allowedExpandDocumentRefPaths:
                    - <e.g. essay/outline.md>
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the `<name>` PLAN node. Emit EXACTLY N
                  children in this exact order. N MUST equal the
                  number of recipes in allowedSubTaskRecipes.

                  KIND 1 — <description matching subgoal sg1>
                  <one-line literal JSON skeleton for child 1>

                  KIND 2 — <description matching sg2>
                  <one-line literal JSON skeleton for child 2>

                  ...

                  Output contract — ONLY these N children:
                      {"children": [<KIND 1>, <KIND 2>, ...]}

                  Do not omit any KIND. Do not add extras. The
                  number of children MUST be exactly N.

            ── EXAMPLE (essay-style pipeline, N=3) ──

                name: my-essay-pipeline
                description: |
                  Produces an essay through outline → chapters → aggregation.
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - outline_loop
                    - chapter_loop
                    - aggregator_run
                  recipesOnlyViaExpand:
                    - chapter_loop
                  allowedExpandDocumentRefPaths:
                    - essay/outline.md
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the my-essay-pipeline PLAN node. Emit
                  EXACTLY 3 children, one per recipe in
                  allowedSubTaskRecipes [outline_loop, chapter_loop,
                  aggregator_run], in this exact order:

                  KIND 1 — WORKER outline_loop (produces essay/outline.md):
                  {"taskKind":"WORKER","goal":"Draft plot, cast, and outline.","taskSpec":{"recipe":"outline_loop"}}

                  KIND 2 — EXPAND_FROM_DOC over outline (one chapter per item):
                  {"taskKind":"EXPAND_FROM_DOC","goal":"Run one chapter_loop per outline item.",
                   "taskSpec":{"documentRef":{"path":"essay/outline.md"},
                               "treeMode":"FLAT",
                               "childTemplate":{"taskKind":"WORKER","recipe":"chapter_loop","goal":"Write the chapter."}}}

                  KIND 3 — WORKER aggregator_run (consolidates chapters → final-essay.md):
                  {"taskKind":"WORKER","goal":"Consolidate chapters into the final essay and post the inbox notification.","taskSpec":{"recipe":"aggregator_run"}}

                  Output contract — EXACTLY these 3 children, no fewer:
                      {"children":[<KIND 1>,<KIND 2>,<KIND 3>]}

                  Do not omit KIND 3. The number of children MUST be 3.

            ── Language ──

            The promptPrefix you generate MUST be in English (the
            runtime PLAN-LLM reads it as orchestration code). The
            user-facing content language is carried separately by
            the goal text and is not your concern here.

            ── promptPrefix is a Pebble template ──

            promptPrefix is rendered through Pebble before the
            PLAN-LLM sees it, so plain prose passes through verbatim.
            If you need a tier-aware variant (rare for PLAN nodes),
            you may use:
                {% if tier == "small" %}…{% else %}…{% endif %}
            with `elseif` (NOT `elif`). Available variables:
            tier, model, provider, mode, profile, recipe, engine,
            params. Avoid Pebble syntax unless you actually need it
            — plain text is the safer default. Anything that looks
            like {{ … }} or {% … %} but isn't intended as a
            template will be parsed as Pebble; escape with
            {% raw %}…{% endraw %} if you must include braces
            literally.

            ── justifications map ──

            Every constraint-key you set in the YAML (params.X or
            promptPrefix) MUST point to an sg-id that exists in
            the subgoal list.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final RecipeLoader recipeLoader;
    private final PromptTemplateRenderer promptTemplateRenderer;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.MARVIN_RECIPE;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        return true;
    }

    @Override
    public void appendProposingContext(
            StringBuilder sb, ArchitectState state,
            List<ResolvedRecipe> availableRecipes) {
        sb.append("Available sub-recipes in the project (excluding "
                + "your own _slart/* generated bucket):\n");
        if (availableRecipes.isEmpty()) {
            sb.append("  (none)\n\n")
                    .append("Because there are NO project sub-recipes:\n")
                    .append("  - DO NOT set params.allowedSubTaskRecipes "
                            + "(omit the field entirely).\n")
                    .append("  - DO NOT set params.recipesOnlyViaExpand.\n")
                    .append("  - Inventing recipe names ('web-research', "
                            + "'analyze', 'marvin-worker', etc.) will be "
                            + "rejected by the validator — those names "
                            + "do not resolve at runtime.\n")
                    .append("  - Drive the plan via the promptPrefix "
                            + "alone; let Marvin's PLAN-LLM pick task "
                            + "kinds (WORKER without recipe = generic "
                            + "ford worker, EXPAND_FROM_DOC, etc.) at "
                            + "runtime.\n\n");
        } else {
            for (ResolvedRecipe r : availableRecipes) {
                sb.append("  - ").append(r.name())
                        .append(" [engine=").append(r.engine())
                        .append("]: ")
                        .append(abbrev(r.description(), 100))
                        .append("\n");
            }
            sb.append("\nIf your subgoals map to any of these recipes, "
                    + "set allowedSubTaskRecipes to the matching subset "
                    + "and reference each recipe in the promptPrefix as "
                    + "`taskSpec.recipe`. Remember the KIND-block parity "
                    + "rule: the number of KIND blocks MUST equal the "
                    + "size of allowedSubTaskRecipes. Use ONLY the names "
                    + "listed above — every name must resolve to a real "
                    + "project recipe.\n\n");
        }
    }

    @Override
    public String expectedEngineName() {
        return "marvin";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        Object pp = recipeMap.get("promptPrefix");
        if (!(pp instanceof String ppStr) || ppStr.isBlank()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_MARVIN_PROMPT_PREFIX).passed(false)
                    .message("MARVIN_RECIPE must declare a non-blank "
                            + "top-level 'promptPrefix' (the PLAN-LLM "
                            + "instruction)")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_PROMPT_PREFIX).passed(true)
                .message("promptPrefix present (" + ppStr.length()
                        + " chars)").build());

        // Recipes carry promptPrefix as a Pebble template (tier /
        // mode / model conditions live inside the body). Compile
        // it now so a syntax slip surfaces at validation time, not
        // at first turn.
        try {
            promptTemplateRenderer.compile(ppStr);
            report.add(ValidationCheck.builder()
                    .rule(RULE_PROMPT_PREFIX_TEMPLATE_VALID).passed(true)
                    .message("promptPrefix is a valid Pebble template")
                    .build());
        } catch (PromptTemplateException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_PROMPT_PREFIX_TEMPLATE_VALID).passed(false)
                    .message("promptPrefix is not a valid Pebble template: "
                            + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }

        Object params = recipeMap.get("params");
        if (!(params instanceof Map<?, ?>)) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPE_SHAPE).passed(false)
                    .message("MARVIN_RECIPE must declare a 'params' map "
                            + "(may be empty for a default Marvin run)")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_MARVIN_RECIPE_SHAPE).passed(true)
                .message("params block present").build());

        // MARVIN_RECIPE: every entry in allowedSubTaskRecipes /
        // recipesOnlyViaExpand MUST be a real project recipe and
        // must NOT be a reserved engine name. Empirical:
        // PROPOSING fabricates plausible-sounding names ('ford',
        // 'web-research', 'marvin-worker') when the project has
        // no real sub-recipes — Marvin's runtime PLAN-validator
        // rejects those, and the whole pipeline aborts. Catching
        // it here saves a wallclock cycle through Marvin.
        @SuppressWarnings("unchecked")
        ValidationCheck recipeCheck = checkAllowedSubTaskRecipes(
                (Map<String, Object>) params, process);
        report.add(recipeCheck);
        return recipeCheck.isPassed() ? null : recipeCheck;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        // Same protection for recipe names as for sg-ids: echo
        // the actual project recipe inventory so the LLM can't
        // fabricate plausible-sounding names. allowedSubTaskRecipes
        // and recipesOnlyViaExpand take recipe names, never engine
        // labels.
        List<String> available = listAvailableRecipeNames(process);
        StringBuilder sb = new StringBuilder();
        sb.append("\nValid recipe names (use ONLY these in "
                + "allowedSubTaskRecipes / recipesOnlyViaExpand): ");
        if (available.isEmpty()) {
            sb.append("(none — leave allowedSubTaskRecipes "
                    + "and recipesOnlyViaExpand absent).\n");
        } else {
            sb.append(String.join(", ", available)).append(".\n");
        }
        sb.append("\nEmit a corrected recipe YAML as a JSON object "
                + "with a valid name, engine: marvin, "
                + "params.allowedSubTaskRecipes / "
                + "params.recipesOnlyViaExpand / "
                + "params.allowedExpandDocumentRefPaths / "
                + "params.disallowedTaskKinds set per the "
                + "system-prompt rules, a non-empty promptPrefix "
                + "with one KIND block per recipe, and "
                + "justifications all pointing to existing "
                + "sg-ids from the list above.");
        return sb.toString();
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Validates that every entry in {@code params.allowedSubTaskRecipes}
     * and {@code params.recipesOnlyViaExpand} is a real project
     * recipe — both fields hold recipe names (NOT engine names), so
     * a name that doesn't resolve via the recipe-cascade is by
     * definition wrong (whether the LLM hallucinated it freshly or
     * mis-typed an engine label is irrelevant). Also catches
     * duplicate entries.
     */
    private ValidationCheck checkAllowedSubTaskRecipes(
            Map<String, Object> params, ThinkProcessDocument process) {
        List<String> allowed = readStringList(params.get("allowedSubTaskRecipes"));
        List<String> onlyViaExpand = readStringList(params.get("recipesOnlyViaExpand"));

        if (allowed.isEmpty() && onlyViaExpand.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPES_EXIST).passed(true)
                    .message("no allowedSubTaskRecipes / recipesOnlyViaExpand "
                            + "constraints — no names to validate")
                    .build();
        }

        Set<String> available = new LinkedHashSet<>();
        try {
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")) {
                    available.add(r.name());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' VALIDATING failed listing recipes: {}",
                    process.getId(), e.toString());
        }

        List<String> unknown = new ArrayList<>();
        for (String name : allowed) {
            if (!available.contains(name)) unknown.add(name);
        }
        for (String name : onlyViaExpand) {
            if (!available.contains(name) && !unknown.contains(name)) {
                unknown.add(name);
            }
        }

        Set<String> seen = new HashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        for (String name : allowed) {
            if (!seen.add(name)) dupes.add(name);
        }

        if (!unknown.isEmpty() || !dupes.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            if (!unknown.isEmpty()) {
                msg.append("recipe(s) not found in project: ")
                        .append(String.join(", ", unknown)).append(". ");
            }
            if (!dupes.isEmpty()) {
                msg.append("duplicate recipe name(s) in "
                                + "allowedSubTaskRecipes: ")
                        .append(String.join(", ", dupes)).append(". ");
            }
            if (available.isEmpty()) {
                msg.append("Project has no available recipes — drop the "
                        + "allowedSubTaskRecipes constraint entirely.");
            } else {
                msg.append("Available: ")
                        .append(String.join(", ", available)).append(".");
            }
            return ValidationCheck.builder()
                    .rule(RULE_MARVIN_RECIPES_EXIST).passed(false)
                    .message(msg.toString().trim())
                    .build();
        }

        return ValidationCheck.builder()
                .rule(RULE_MARVIN_RECIPES_EXIST).passed(true)
                .message("all " + (allowed.size() + onlyViaExpand.size())
                        + " recipe name(s) resolve to project recipes")
                .build();
    }

    private List<String> listAvailableRecipeNames(ThinkProcessDocument process) {
        try {
            List<String> names = new ArrayList<>();
            for (ResolvedRecipe r : recipeLoader.listAll(
                    process.getTenantId(), process.getProjectId())) {
                if (!r.name().startsWith("_slart/")) names.add(r.name());
            }
            return names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
