package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.vogon.StrategyResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * VALIDATING phase — hard validator gate after PROPOSING.
 * Verifies that the {@link RecipeDraft} is parseable as a Vogon
 * recipe, that every justification reference resolves to an
 * existing subgoal, and that the embedded
 * {@code params.strategyPlanYaml} survives
 * {@link StrategyResolver#parseStrategy} without throwing.
 *
 * <p>On failure: sets {@link ArchitectState#setPendingRecovery}
 * pointing back at PROPOSING with a corrective hint. Same
 * recovery contract as BindingPhase. Engine handles the rollback
 * + escalation logic.
 *
 * <p>Pure logic — no LLM, no I/O beyond the YAML parser.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ValidatingPhase {

    public static final String RULE_RECIPE_PRESENT =
            "proposed-recipe-present";
    public static final String RULE_YAML_PARSES =
            "recipe-yaml-parses";
    public static final String RULE_RECIPE_SHAPE =
            "recipe-has-name-engine";
    public static final String RULE_VOGON_STRATEGY_PARSES =
            "embedded-strategy-yaml-parses";
    public static final String RULE_MARVIN_RECIPE_SHAPE =
            "marvin-recipe-shape";
    public static final String RULE_MARVIN_PROMPT_PREFIX =
            "marvin-recipe-prompt-prefix-present";
    public static final String RULE_PROMPT_PREFIX_TEMPLATE_VALID =
            "recipe-prompt-prefix-pebble-template-valid";
    public static final String RULE_MARVIN_RECIPES_EXIST =
            "marvin-recipe-allowed-recipes-exist";
    public static final String RULE_VOGON_WORKER_RECIPES_EXIST =
            "vogon-strategy-worker-recipes-exist";
    public static final String RULE_JUSTIFICATION_RESOLVES =
            "justification-subgoal-id-resolves";
    public static final String RULE_SCHEMA_TYPE_SUPPORTED =
            "outputSchemaType-supported";

    private final RecipeLoader recipeLoader;
    private final PromptTemplateRenderer promptTemplateRenderer;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        RecipeDraft draft = state.getProposedRecipe();
        if (draft == null) {
            state.setFailureReason("VALIDATING entered without a "
                    + "proposedRecipe — PROPOSING must run first");
            return;
        }

        List<ValidationCheck> report = new ArrayList<>();
        ValidationCheck firstFail = null;

        // 1. SchemaType supported (M5: VOGON_STRATEGY + MARVIN_RECIPE).
        // The supported set is enumerated below — adding a new
        // OutputSchemaType requires a corresponding branch.
        // No early-fail since both currently-defined values are OK.

        // 2. YAML parses at all.
        Map<String, Object> recipeMap = null;
        if (firstFail == null) {
            try {
                Object loaded = new Yaml().load(draft.getYaml());
                if (!(loaded instanceof Map<?, ?> m)) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_YAML_PARSES)
                            .passed(false)
                            .message("recipe yaml top-level is not a mapping "
                                    + "(got " + (loaded == null
                                            ? "null" : loaded.getClass().getSimpleName())
                                    + ")")
                            .build();
                    report.add(v);
                    firstFail = v;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    recipeMap = cast;
                    report.add(ValidationCheck.builder()
                            .rule(RULE_YAML_PARSES).passed(true)
                            .message("recipe yaml is a valid mapping").build());
                }
            } catch (RuntimeException e) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_YAML_PARSES)
                        .passed(false)
                        .message("recipe yaml parse error: " + e.getMessage())
                        .build();
                report.add(v);
                firstFail = v;
            }
        }

        // 3. Recipe has the expected shape: name + matching engine.
        String expectedEngine = switch (state.getOutputSchemaType()) {
            case VOGON_STRATEGY -> "vogon";
            case MARVIN_RECIPE -> "marvin";
        };
        if (firstFail == null && recipeMap != null) {
            Object name = recipeMap.get("name");
            Object engine = recipeMap.get("engine");
            if (!(name instanceof String n) || n.isBlank()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml missing top-level 'name' string")
                        .build();
                report.add(v);
                firstFail = v;
            } else if (!(engine instanceof String e) || e.isBlank()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml missing top-level 'engine' string")
                        .build();
                report.add(v);
                firstFail = v;
            } else if (!expectedEngine.equals(((String) engine).trim())) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(false)
                        .message("recipe yaml engine='" + engine
                                + "' but " + state.getOutputSchemaType()
                                + " schemaType requires engine='"
                                + expectedEngine + "'")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
                report.add(ValidationCheck.builder()
                        .rule(RULE_RECIPE_SHAPE).passed(true)
                        .message("recipe has name + engine: " + expectedEngine)
                        .build());
            }
        }

        // 4a. VOGON_STRATEGY: embedded strategyPlanYaml parses via
        //     Vogon's resolver.
        // 4b. MARVIN_RECIPE: promptPrefix + (optional) params shape.
        if (firstFail == null && recipeMap != null
                && state.getOutputSchemaType() == OutputSchemaType.VOGON_STRATEGY) {
            Object params = recipeMap.get("params");
            if (!(params instanceof Map<?, ?> pm)) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                        .message("recipe yaml missing 'params' map")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
                Object spy = ((Map<String, Object>) pm).get("strategyPlanYaml");
                if (!(spy instanceof String spyStr) || spyStr.isBlank()) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                            .message("recipe yaml missing "
                                    + "'params.strategyPlanYaml' string")
                            .build();
                    report.add(v);
                    firstFail = v;
                } else {
                    de.mhus.vance.api.vogon.StrategySpec parsedStrategy = null;
                    try {
                        parsedStrategy = StrategyResolver.parseStrategy(spyStr,
                                "slartibartfast/" + draft.getName());
                        report.add(ValidationCheck.builder()
                                .rule(RULE_VOGON_STRATEGY_PARSES).passed(true)
                                .message("strategyPlanYaml parses cleanly")
                                .build());
                    } catch (RuntimeException e) {
                        ValidationCheck v = ValidationCheck.builder()
                                .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                                .message("StrategyResolver rejected "
                                        + "strategyPlanYaml: " + e.getMessage())
                                .build();
                        report.add(v);
                        firstFail = v;
                    }
                    if (firstFail == null && parsedStrategy != null) {
                        ValidationCheck workerCheck =
                                validateVogonWorkerRecipes(
                                        parsedStrategy, process);
                        report.add(workerCheck);
                        if (!workerCheck.isPassed()) firstFail = workerCheck;
                    }
                }
            }
        }

        // 4b. MARVIN_RECIPE: must have a non-blank promptPrefix
        //     (the PLAN-LLM-instruction text) and a params block
        //     (engine constraints, may be empty). Deep validation
        //     of the constraint values would happen at runtime
        //     when Marvin actually runs this recipe — here we
        //     just check shape.
        if (firstFail == null && recipeMap != null
                && state.getOutputSchemaType() == OutputSchemaType.MARVIN_RECIPE) {
            Object pp = recipeMap.get("promptPrefix");
            if (!(pp instanceof String ppStr) || ppStr.isBlank()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_MARVIN_PROMPT_PREFIX).passed(false)
                        .message("MARVIN_RECIPE must declare a non-blank "
                                + "top-level 'promptPrefix' (the PLAN-LLM "
                                + "instruction)")
                        .build();
                report.add(v);
                firstFail = v;
            } else {
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
                            .message("promptPrefix is a valid Pebble template").build());
                } catch (PromptTemplateException e) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_PROMPT_PREFIX_TEMPLATE_VALID).passed(false)
                            .message("promptPrefix is not a valid Pebble template: "
                                    + e.getMessage())
                            .build();
                    report.add(v);
                    firstFail = v;
                }
            }
            Object params = recipeMap.get("params");
            if (firstFail == null
                    && (!(params instanceof Map<?, ?>))) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_MARVIN_RECIPE_SHAPE).passed(false)
                        .message("MARVIN_RECIPE must declare a 'params' map "
                                + "(may be empty for a default Marvin run)")
                        .build();
                report.add(v);
                firstFail = v;
            } else if (firstFail == null) {
                report.add(ValidationCheck.builder()
                        .rule(RULE_MARVIN_RECIPE_SHAPE).passed(true)
                        .message("params block present").build());
            }

            // MARVIN_RECIPE: every entry in allowedSubTaskRecipes /
            // recipesOnlyViaExpand MUST be a real project recipe and
            // must NOT be a reserved engine name. Empirical:
            // PROPOSING fabricates plausible-sounding names ('ford',
            // 'web-research', 'marvin-worker') when the project has
            // no real sub-recipes — Marvin's runtime PLAN-validator
            // rejects those, and the whole pipeline aborts. Catching
            // it here saves a wallclock cycle through Marvin.
            if (firstFail == null && params instanceof Map<?, ?> pmap) {
                @SuppressWarnings("unchecked")
                ValidationCheck recipeCheck = checkAllowedSubTaskRecipes(
                        (Map<String, Object>) pmap, process);
                report.add(recipeCheck);
                if (!recipeCheck.isPassed()) firstFail = recipeCheck;
            }
        }

        // 5. Justification refs resolve to subgoal ids.
        //    Empirically the LLM occasionally emits a comma-
        //    separated value ("sg1, sg2, sg3") when one constraint
        //    covers several subgoals — JSON forbids duplicate keys
        //    so it can't list multiple entries. We split + check
        //    each part rather than reject the whole entry on a
        //    purely formatting choice. Every part still has to be
        //    a real sg-id; otherwise the entry is rejected with a
        //    pointer to the unresolved name(s).
        if (firstFail == null) {
            Set<String> sgIds = new HashSet<>();
            for (Subgoal sg : state.getSubgoals()) sgIds.add(sg.getId());
            int totalRefs = 0;
            for (Map.Entry<String, String> e :
                    draft.getJustifications().entrySet()) {
                String raw = e.getValue();
                List<String> parts = splitSgIdList(raw);
                List<String> unknown = new ArrayList<>();
                for (String part : parts) {
                    if (!sgIds.contains(part)) unknown.add(part);
                }
                if (parts.isEmpty() || !unknown.isEmpty()) {
                    String missing = parts.isEmpty()
                            ? raw
                            : String.join(", ", unknown);
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_JUSTIFICATION_RESOLVES)
                            .passed(false)
                            .offendingId(e.getKey())
                            .message("justification '" + e.getKey()
                                    + "' → '" + raw
                                    + "' references non-existent subgoal: "
                                    + missing)
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                } else {
                    totalRefs += parts.size();
                }
            }
            if (firstFail == null) {
                report.add(ValidationCheck.builder()
                        .rule(RULE_JUSTIFICATION_RESOLVES).passed(true)
                        .message(draft.getJustifications().size()
                                + " justification(s) all resolve to subgoals "
                                + "(" + totalRefs + " sg-id ref(s) checked)")
                        .build());
            }
        }

        // 6. Recipe-present check (defensive — already gated above).
        report.add(0, ValidationCheck.builder()
                .rule(RULE_RECIPE_PRESENT).passed(true)
                .message("RecipeDraft present").build());

        state.setValidationReport(report);

        if (firstFail != null) {
            String hint = buildHint(report, state, process);
            state.setPendingRecovery(RecoveryRequest.builder()
                    .fromPhase(ArchitectStatus.VALIDATING)
                    .toPhase(ArchitectStatus.PROPOSING)
                    .reason(firstFail.getRule())
                    .hint(hint)
                    .offendingId(firstFail.getOffendingId())
                    .build());

            appendIteration(state,
                    "draft '" + draft.getName() + "', "
                            + draft.getJustifications().size()
                            + " justifications",
                    "FAILED — " + countFailed(report) + " violation(s); "
                            + "rollback to PROPOSING",
                    PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);

            log.info("Slartibartfast id='{}' VALIDATING failed — {} violations, "
                            + "requesting PROPOSING re-run",
                    process.getId(), countFailed(report));
        } else {
            appendIteration(state,
                    "draft '" + draft.getName() + "'",
                    "passed — yaml + strategy + " + draft.getJustifications().size()
                            + " justifications all valid",
                    PhaseIteration.IterationOutcome.PASSED);
        }
    }

    // ──────────────────── Helpers ────────────────────

    private String buildHint(
            List<ValidationCheck> report, ArchitectState state,
            ThinkProcessDocument process) {
        StringBuilder sb = new StringBuilder();
        sb.append("VALIDATING rejected the previous recipe. "
                + "Violations — address EVERY one:\n");
        int shown = 0;
        for (ValidationCheck v : report) {
            if (v.isPassed()) continue;
            if (shown >= 5) break;
            sb.append("- [").append(v.getRule()).append("] ")
                    .append(v.getMessage()).append("\n");
            shown++;
        }
        // Echo the existing sg-ids verbatim so the LLM can't drift to
        // hallucinated names like 'sg9' when only sg1..sgN exist.
        sb.append("\nValid sg-ids (use ONLY these as justification "
                + "values): ");
        boolean first = true;
        for (Subgoal sg : state.getSubgoals()) {
            if (!first) sb.append(", ");
            sb.append(sg.getId());
            first = false;
        }
        sb.append(".\n");

        boolean isMarvin = state.getOutputSchemaType()
                == OutputSchemaType.MARVIN_RECIPE;
        if (isMarvin) {
            // Same protection for recipe names as for sg-ids: echo
            // the actual project recipe inventory so the LLM can't
            // fabricate plausible-sounding names. allowedSubTaskRecipes
            // and recipesOnlyViaExpand take recipe names, never engine
            // labels.
            List<String> available = listAvailableRecipeNames(process);
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
        } else {
            sb.append("\nEmit a corrected recipe YAML as a JSON object "
                    + "with a valid name, engine: vogon, "
                    + "params.strategyPlanYaml (parseable by Vogon), "
                    + "and justifications all pointing to existing "
                    + "sg-ids from the list above.");
        }
        return sb.toString();
    }

    /**
     * Validates that every entry in {@code params.allowedSubTaskRecipes}
     * and {@code params.recipesOnlyViaExpand} is a real project
     * recipe — both fields hold recipe names (NOT engine names), so
     * a name that doesn't resolve via the recipe-cascade is by
     * definition wrong (whether the LLM hallucinated it freshly or
     * mis-typed an engine label is irrelevant). Also catches
     * duplicate entries.
     */
    /**
     * Validates every {@code worker:} reference in the generated
     * Vogon strategy. Each must resolve to a known recipe — the
     * common failure we want to catch is the LLM emitting a
     * tool name (e.g. {@code doc_edit}, {@code web_search}) as
     * worker, which would parse cleanly but fail at run-time
     * when Vogon tries to spawn the child. Walks top-level phases
     * AND nested loop sub-phases. Static {@code ${...}}
     * substitutions are ignored — those are resolved at runtime
     * via params.
     */
    private ValidationCheck validateVogonWorkerRecipes(
            de.mhus.vance.api.vogon.StrategySpec strategy,
            ThinkProcessDocument process) {
        Set<String> workersUsed = new LinkedHashSet<>();
        collectWorkerNames(strategy.getPhases(), workersUsed);

        if (workersUsed.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_VOGON_WORKER_RECIPES_EXIST).passed(true)
                    .message("no worker references to validate")
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
            log.warn("Slartibartfast id='{}' VALIDATING failed listing "
                            + "recipes for worker-check: {}",
                    process.getId(), e.toString());
        }

        List<String> unknown = new ArrayList<>();
        for (String w : workersUsed) {
            if (!available.contains(w)) unknown.add(w);
        }
        if (unknown.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_VOGON_WORKER_RECIPES_EXIST).passed(true)
                    .message(workersUsed.size() + " worker reference(s) "
                            + "resolve to known recipes")
                    .build();
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Vogon strategy references unknown recipe(s) as "
                + "worker: ").append(unknown).append(".\n\n");
        msg.append("Common mistake: using a TOOL name (e.g. doc_edit, "
                + "doc_create_text, web_search, scratch_write) where a "
                + "RECIPE name is required. Tools are called inside a "
                + "worker's turn; the worker itself must be a recipe "
                + "with an engine bound to it.\n\n");
        msg.append("POSSIBLE OPTIONS for the worker: field — pick one "
                + "per phase based on the work the phase does:\n");
        msg.append("- 'ford' — generalist single-task worker. Default "
                + "choice for most drafting / research / analysis / "
                + "consolidation phases. Use when one focused turn is "
                + "enough.\n");
        msg.append("- 'marvin-worker' — sub-task decomposer. Use for "
                + "long-form outputs (multi-chapter drafts, broad "
                + "reviews) where one Ford turn would be too much.\n");
        msg.append("- 'analyze' — read-only analyst recipe. Use for "
                + "review / critique phases that should not write.\n");
        msg.append("- 'code-read' — read-only code-review recipe. "
                + "Use only when the phase reads source code.\n");
        if (!available.isEmpty()) {
            // List the actually-installed project recipes too —
            // tells the LLM exactly what's available right now.
            java.util.List<String> projectLocal = new java.util.ArrayList<>();
            for (String name : available) {
                if (!java.util.Set.of("ford", "marvin-worker",
                        "analyze", "code-read").contains(name)) {
                    projectLocal.add(name);
                }
            }
            if (!projectLocal.isEmpty()) {
                msg.append("- Project-local recipes also available: ")
                        .append(projectLocal).append("\n");
            }
        }
        return ValidationCheck.builder()
                .rule(RULE_VOGON_WORKER_RECIPES_EXIST).passed(false)
                .message(msg.toString())
                .build();
    }

    private static void collectWorkerNames(
            List<de.mhus.vance.api.vogon.PhaseSpec> phases,
            Set<String> out) {
        if (phases == null) return;
        for (de.mhus.vance.api.vogon.PhaseSpec p : phases) {
            String w = p.getWorker();
            if (w != null && !w.isBlank() && !w.contains("${")) {
                out.add(w.trim());
            }
            de.mhus.vance.api.vogon.LoopSpec loop = p.getLoop();
            if (loop != null) {
                collectWorkerNames(loop.getSubPhases(), out);
            }
        }
    }

    private ValidationCheck checkAllowedSubTaskRecipes(
            Map<String, Object> params, ThinkProcessDocument process) {
        List<String> allowed = readStringList(params.get("allowedSubTaskRecipes"));
        List<String> onlyViaExpand = readStringList(params.get("recipesOnlyViaExpand"));

        if (allowed.isEmpty() && onlyViaExpand.isEmpty()) {
            // Recipe-author chose to leave the constraint open — no
            // names to validate. Marvin will run with the full
            // recipe catalog at runtime.
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

        // Duplicate detection: any name that shows up >1 in
        // allowedSubTaskRecipes is a duplicate.
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

    /**
     * Splits a justification value into individual sg-id references.
     * Tolerates a single value ("sg1") or a comma-separated list
     * ("sg1, sg2, sg3"). Empty fragments and surrounding whitespace
     * are dropped; the order of the result follows the input.
     */
    private static List<String> splitSgIdList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
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

    private static long countFailed(List<ValidationCheck> report) {
        return report.stream().filter(v -> !v.isPassed()).count();
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.VALIDATING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.VALIDATING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }
}
