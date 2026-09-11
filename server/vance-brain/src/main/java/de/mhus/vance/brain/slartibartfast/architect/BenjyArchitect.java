package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.benjy.BenjyFeatureConfig;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Benjy recipe architect. Produces the <b>outer</b> recipe of a Benjy
 * orchestration suite — {@code engine: benjy} plus the params the
 * engine parses fail-fast at first loop entry. It deliberately does
 * NOT generate the sub-recipes (doer, controller LightLm profiles,
 * escalation target): Slart emits one artefact per run, a Benjy
 * configuration is a suite, and the bundled {@code benjy-*} profiles
 * and doers cover the common cases. References are validated to
 * resolve — the same contract {@code MarvinArchitect} enforces for
 * {@code allowedSubTaskRecipes}. Generating missing sub-recipes is
 * the recursive-spawn open point shared by all recipe architects
 * (slartibartfast-engine.md §11).
 *
 * <p>Validation is <b>delegated, not duplicated</b>: the shape check
 * calls the engine's own {@link BenjyFeatureConfig#fromParams} — the
 * identical fail-fast logic that would otherwise kill the first spawn
 * turn. The architect moves that failure into Slart's VALIDATING
 * gate, where the recovery loop can fix it with a concrete hint
 * instead of leaving a dead process behind. On top of the engine
 * check it verifies that every referenced recipe resolves in the
 * project and that the reference <i>kinds</i> match the call sites:
 * controller features are LightLlm profiles ({@code internal: true},
 * the gate {@code LightLlmService} itself enforces), doer and
 * escalation target are spawnable workers.
 *
 * <p>Author-only, like {@code MagratheaArchitect}: the bundled
 * {@code benjy-architect} recipe sets {@code planOnly: true}, so the
 * run ends at DONE after PERSISTING. A Benjy run is a long-lived
 * iterative worker with its own doer spawns and controller calls —
 * its cost profile has no place inside an authoring run, and
 * EXECUTION_VALIDATING's file-path heuristics don't apply to a
 * params-only recipe. {@link #wantsExecutionValidation()} and
 * {@link #wantsPathPersistenceCheck()} are declared {@code false}
 * defensively so a caller who forgets {@code planOnly} does not drive
 * Slart into a pointless recovery loop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BenjyArchitect implements SchemaArchitect {

    public static final String RULE_BENJY_PARAMS_MAP_PRESENT = "benjy-recipe-params-map-present";
    public static final String RULE_BENJY_FEATURE_CONFIG = "benjy-recipe-feature-config-valid";
    public static final String RULE_BENJY_RECIPE_REFS_EXIST = "benjy-recipe-references-exist";
    public static final String RULE_BENJY_RECIPE_KINDS = "benjy-recipe-reference-kinds-match";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce a Benjy
            RECIPE — the outer configuration of Vance's iterative
            orchestration engine for small/local models. The engine
            carries the loop discipline; the recipe switches the
            semantic stages on and parameterises them.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case, typically 'benjy-<domain>'>",
                  "yaml":           "<full recipe YAML, see structure below>",
                  "justifications": { "<constraint-key>": "<sg-id>", ... },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this feature set and these caps — 1-2 sentences>"
                }

            All five fields are required. The "justifications" map
            binds constraint keys to the sg-ids from the subgoals
            list — convention: "name", "params.doRecipe",
            "params.features.<feature>", "params.taskTypes",
            "params.workTarget.kind". A missing or dangling binding
            triggers a re-prompt.

            YAML structure (mandatory parts marked):
                title: "<display name>"               # optional
                listed: true                          # optional — user-facing picker
                category: <category-id>               # optional (coding, research, workers, ...)
                description: |
                  <one paragraph — what this worker does and when to pick it>
                engine: benjy                          # MANDATORY, exactly this value
                params:                                # MANDATORY map
                  doRecipe: <recipe-name>             # MANDATORY — Ford doer spawned per item
                  taskTypes: [info, coding, planning, analysis]  # subset; default: all four
                  features:                           # interpret is MANDATORY
                    interpret:  { recipe: <light-llm-profile> } # goal → criteria + first items
                    route:      { recipe: <light-llm-profile> }  # optional — off = mechanical fallback policy
                    check:      { command: "<shell command>" }   # optional — mechanical verification (coding items)
                    evaluate:   { recipe: <light-llm-profile> }  # optional — per-item LLM judgement
                    reflect:    { recipe: <light-llm-profile> }  # optional — goal-level gate before DONE
                    escalation: { recipe: <spawnable-recipe> }   # optional — stronger worker for stuck items
                  criteriaSources: [{ ref: "<doc path>" }]      # optional — requirement docs
                  maxStagnation: 15        # tasks without observable progress → checkpoint question
                  maxReflectNo: 3         # reflect 'no' verdicts → checkpoint question (reflect runs only)
                  maxItemAttempts: 3      # mechanical retries per item
                  maxWallclockMinutes: 30
                  maxTokens: 2000000      # controller (LightLm) budget per run
                  maxToolCalls: 15        # per-item tool budget handed to the doer
                  maxInitialItems: 5      # items per batch; raise only when the task text makes the structure evident
                  workTarget:
                    kind: WORK            # WORK (server workspace) or CLIENT (user's Foot files)

            FEATURE SEMANTICS (pick per goal, don't copy blindly):
            - info items run do → close: no check, no evaluate.
            - coding items run do → check → evaluate → close when the
              stages are on. check needs a REAL project build/test
              command — pin it only if the goal names one; a wrong
              default fails every run's checks. Without check the
              chain degrades to do → evaluate → close.
            - route: false is the cheap mode — no LLM at branch
              points, pure engine mechanics (retry to cap →
              escalation → BLOCKED). Fit for mechanical bulk lists.
            - reflect is the goal-level gate before DONE: on for
              quality-relevant goals, off when "queue empty" IS the
              goal (mechanical batches).
            - escalation delegates stuck items to a STRONGER worker
              (bigger model). Only meaningful if one exists in the
              project. Without it, escalation ends BLOCKED.

            RULES:
            - doRecipe, escalation and every controller profile must
              be an EXISTING recipe from the available-recipes list
              in the user message — never invent names. Controller
              profiles (interpret/route/evaluate/reflect) are
              internal LightLlm profiles; the doer and the escalation
              target are spawnable worker recipes.
            - Turn a feature OFF by omitting it (or writing
              "false"), never by an empty recipe name.
            - Caps are safety nets, not style: keep the defaults
              unless the goal justifies a change.
            - prose-style fields are read by downstream LLMs — write
              them in English.

            If you violate this contract the validator rejects your
            output and asks you to correct it.
            """;

    private final RecipeLoader recipeLoader;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.BENJY_RECIPE;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        // doRecipe, escalation target and the controller profiles are
        // all recipe references — the LLM needs the inventory to pick
        // names that resolve.
        return true;
    }

    @Override
    public void appendProposingContext(StringBuilder sb, ArchitectState state, List<ResolvedRecipe> availableRecipes) {
        sb.append("Available recipes for params.doRecipe, "
                + "params.features.*.recipe and params.features.escalation.recipe "
                + "(excluding the _slart/* generated bucket):\n");
        if (availableRecipes.isEmpty()) {
            sb.append("  (none)\n\n")
                    .append("Because there are NO project recipes, no valid "
                            + "Benjy recipe can be built — doRecipe and "
                            + "features.interpret are mandatory references.\n");
            return;
        }
        for (ResolvedRecipe r : availableRecipes) {
            sb.append("  - ")
                    .append(r.name())
                    .append(" [engine=")
                    .append(r.engine())
                    .append(r.internal() ? ", internal LightLm profile" : ", spawnable worker")
                    .append("]: ")
                    .append(abbrev(r.description(), 100))
                    .append("\n");
        }
        sb.append("\nController features (interpret / route / evaluate / "
                + "reflect) must reference internal LightLm profiles; "
                + "doRecipe and escalation must reference spawnable workers. "
                + "Use the bundled benjy-interpret / -route / -evaluate / "
                + "-reflect profiles and benjy-do-* doers when they fit.\n");
    }

    @Override
    public String expectedEngineName() {
        return "benjy";
    }

    @Override
    public boolean wantsPathPersistenceCheck() {
        // A Benjy outer recipe is params-only — no promptPrefix, no
        // embedded tool calls. The substring check would always fail
        // by construction (same reasoning as ZaphodArchitect).
        return false;
    }

    @Override
    public boolean wantsExecutionValidation() {
        // Author-only (planOnly) — the run ends at DONE after
        // PERSISTING. Declared false defensively so a caller who
        // forgets planOnly does not drive Slart into a recovery loop
        // over a Benjy worker that was never spawned by this run.
        return false;
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft,
            Map<String, Object> recipeMap,
            ThinkProcessDocument process,
            List<ValidationCheck> report) {
        // 1. params map present — everything else lives inside it.
        Object paramsObj = recipeMap.get("params");
        if (!(paramsObj instanceof Map<?, ?>)) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_BENJY_PARAMS_MAP_PRESENT)
                    .passed(false)
                    .message("BENJY_RECIPE must declare a 'params' map")
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_BENJY_PARAMS_MAP_PRESENT)
                .passed(true)
                .message("params block present")
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) paramsObj;

        // 2. Engine's own fail-fast contract — delegated, not
        //    duplicated. fromParams throws with a caller-ready
        //    message on unknown feature keys, unknown task types,
        //    missing interpret/doRecipe or malformed feature values.
        BenjyFeatureConfig cfg;
        try {
            cfg = BenjyFeatureConfig.fromParams(params, process.getId());
        } catch (IllegalArgumentException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_BENJY_FEATURE_CONFIG)
                    .passed(false)
                    .message("params fail the Benjy engine's fail-fast " + "contract: " + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_BENJY_FEATURE_CONFIG)
                .passed(true)
                .message("feature config valid (doRecipe=" + cfg.getDoRecipe() + ", taskTypes=" + cfg.getTaskTypes()
                        + ")")
                .build());

        // 3. Every referenced recipe must resolve in the project.
        Map<String, String> refs = collectReferences(cfg);
        Map<String, ResolvedRecipe> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            try {
                Optional<ResolvedRecipe> hit =
                        recipeLoader.load(process.getTenantId(), process.getProjectId(), ref.getValue());
                if (hit.isPresent()) {
                    resolved.put(ref.getKey(), hit.get());
                } else {
                    unknown.add(ref.getKey() + "=" + ref.getValue());
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Slartibartfast id='{}' VALIDATING failed loading " + "referenced recipe '{}': {}",
                        process.getId(),
                        ref.getValue(),
                        e.toString());
                unknown.add(ref.getKey() + "=" + ref.getValue() + " (parse error: " + e.getMessage() + ")");
            }
        }
        if (!unknown.isEmpty()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_BENJY_RECIPE_REFS_EXIST)
                    .passed(false)
                    .message("referenced recipe(s) not found or broken: "
                            + String.join(", ", unknown)
                            + ". " + inventoryHint(process))
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_BENJY_RECIPE_REFS_EXIST)
                .passed(true)
                .message("all " + refs.size() + " recipe reference(s) resolve")
                .build());

        // 4. Reference kinds must match the call sites: controller
        //    features go through LightLlmService (requires
        //    internal: true), doer and escalation are spawned as
        //    processes (must NOT be internal config profiles).
        List<String> kindViolations = new ArrayList<>();
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            boolean controller = !"doRecipe".equals(ref.getKey()) && !"features.escalation".equals(ref.getKey());
            ResolvedRecipe r = resolved.get(ref.getKey());
            if (controller && !r.internal()) {
                kindViolations.add(ref.getKey() + "=" + ref.getValue() + " is not an internal LightLm profile");
            } else if (!controller && r.internal()) {
                kindViolations.add(
                        ref.getKey() + "=" + ref.getValue() + " is an internal config profile, not a spawnable worker");
            }
        }
        if (!kindViolations.isEmpty()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_BENJY_RECIPE_KINDS)
                    .passed(false)
                    .message(String.join("; ", kindViolations) + ". " + inventoryHint(process))
                    .build();
            report.add(v);
            return v;
        }
        report.add(ValidationCheck.builder()
                .rule(RULE_BENJY_RECIPE_KINDS)
                .passed(true)
                .message("reference kinds match call sites ("
                        + (refs.size() - 2) + " controller profile(s), "
                        + "doer + escalation spawnable)")
                .build());
        return null;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nValid recipe names (use ONLY these, never invent):\n"
                + inventoryHint(process)
                + "\nEmit a corrected recipe JSON with engine: benjy.";
    }

    // ──────────────────── helpers ────────────────────

    /** Constraint-key → recipe-name for every reference the config
     *  carries. doRecipe/escalation are the spawnable pair; the rest
     *  are controller LightLlm profiles. */
    private static Map<String, String> collectReferences(BenjyFeatureConfig cfg) {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("doRecipe", cfg.getDoRecipe());
        refs.put("features.interpret", cfg.getInterpretRecipe());
        if (cfg.getRouteRecipe() != null) refs.put("features.route", cfg.getRouteRecipe());
        if (cfg.getEvaluateRecipe() != null) refs.put("features.evaluate", cfg.getEvaluateRecipe());
        if (cfg.getReflectRecipe() != null) refs.put("features.reflect", cfg.getReflectRecipe());
        if (cfg.getEscalationRecipe() != null) {
            refs.put("features.escalation", cfg.getEscalationRecipe());
        }
        return refs;
    }

    private String inventoryHint(ThinkProcessDocument process) {
        Set<String> controllers = new LinkedHashSet<>();
        Set<String> workers = new LinkedHashSet<>();
        try {
            for (ResolvedRecipe r : recipeLoader.listAll(process.getTenantId(), process.getProjectId())) {
                if (r.name().startsWith("_slart/")) continue;
                (r.internal() ? controllers : workers).add(r.name());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Slartibartfast id='{}' failed listing recipes for the " + "validation hint: {}",
                    process.getId(),
                    e.toString());
            return "Project recipe inventory unavailable.";
        }
        return "Controller LightLm profiles (internal): ["
                + String.join(", ", controllers) + "]. Spawnable workers: ["
                + String.join(", ", workers) + "].";
    }

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
