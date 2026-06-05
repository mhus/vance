package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.vogon.StrategyResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Vogon-strategy architect. Produces recipes with
 * {@code engine: vogon} and an inline {@code params.strategyPlanYaml}
 * describing the phases. The detailed Vogon-strategy schema
 * (phases, gates, scorers, postActions, …) is defined in
 * {@code specification/vogon-engine.md}.
 *
 * <p>Validation gates: (a) {@code params.strategyPlanYaml} parses
 * via {@link StrategyResolver#parseStrategy}; (b) every
 * {@code worker:} reference inside the strategy resolves to a
 * known project recipe (catches the common mistake where the LLM
 * uses a tool name like {@code doc_edit} where a recipe is
 * required).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VogonArchitect implements SchemaArchitect {

    public static final String RULE_VOGON_STRATEGY_PARSES =
            "embedded-strategy-yaml-parses";
    public static final String RULE_VOGON_WORKER_RECIPES_EXIST =
            "vogon-strategy-worker-recipes-exist";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce an
            executable recipe for the Vogon engine. The recipe
            wraps an inline strategyPlanYaml (Vogon strategy).

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML, see structure below>",
                  "justifications": {
                    "<constraint-key>": "<sg-id>",
                    "phases.0.worker": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            YAML structure (mandatory):
                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: vogon
                params:
                  strategyPlanYaml: |
                    name: <strategy-name>
                    version: "1"
                    phases:
                      - name: <phase-name>
                        worker: <recipe-name or ford>
                        workerInput: |
                          <prompt for the worker>
                        gate: { requires: [<phase-name>_completed] }

            PHASE CHAINING — automatic in Vogon:

            Before each phase's worker runs, Vogon prepends a
            discovery block to the workerInput listing every
            completed predecessor phase with its draft-path
            (always under `_vogon-drafts/<process>/<phase>.md`).
            The worker accesses those via `doc_read` (full
            content) or `doc_summary` (1-3-sentence recap).

            Consequence for the workerInput strings you emit:

            - DO write each workerInput as a focused
              "what THIS phase produces" instruction.
            - DO reference predecessors by phase name so the
              worker can match them in the discovery block:
              "Use doc_summary on `create-outline` and doc_read
              on `research-sources` to ground the draft."
            - DO NOT use `${phases.X.result}` /
              `${phases.X.draftPath}` substitutions. They still
              work mechanically but inline the predecessor's
              full reply into every prompt — bloated and
              bypasses the doc_summary shortcut. The injected
              block + doc tools cover the use case better.
            - DO NOT write vague prose like "use the previous
              phase's output" without naming the phase. The
              worker can't infer which entry you meant.

            First-phase exception: the discovery block is
            omitted when there are no predecessors. The
            workerInput is sent verbatim.

            justifications map (mandatory):
            - EVERY constraint-key you set in the YAML MUST point
              here to an sg-id that exists in subgoals.
            - Convention for constraint-keys:
              - "name" for the recipe name
              - "phases.<idx>.worker" for each phase
              - "engine" if you pick anything other than "vogon"
                (should never happen here — output schema type is
                VOGON_STRATEGY)

            confidence:
            - 1.0 minus the speculative share = a coarse heuristic
            - VALIDATING will check this.

            shapeRationale: WHY this exact number of phases in
            this order. Refers to the overall plan shape, not
            individual phases.

            Language: workerInput and prose-style fields are read
            by downstream LLMs as orchestration code — write them
            in English. The user-facing content language is
            carried separately by the goal text.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final RecipeLoader recipeLoader;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.VOGON_STRATEGY;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        return false;
    }

    @Override
    public void appendProposingContext(
            StringBuilder sb, ArchitectState state,
            List<ResolvedRecipe> availableRecipes) {
        // Vogon-strategy phases reference workers directly in the
        // strategyPlanYaml; there's no allowedSubTaskRecipes-style
        // list at the recipe level. The available-recipes block
        // would just bloat the prompt. No-op.
    }

    @Override
    public String expectedEngineName() {
        return "vogon";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        Object params = recipeMap.get("params");
        if (!(params instanceof Map<?, ?> pm)) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                    .message("recipe yaml missing 'params' map")
                    .build();
            report.add(v);
            return v;
        }
        @SuppressWarnings("unchecked")
        Object spy = ((Map<String, Object>) pm).get("strategyPlanYaml");
        if (!(spy instanceof String spyStr) || spyStr.isBlank()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_VOGON_STRATEGY_PARSES).passed(false)
                    .message("recipe yaml missing "
                            + "'params.strategyPlanYaml' string")
                    .build();
            report.add(v);
            return v;
        }
        StrategySpec parsedStrategy;
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
            return v;
        }

        ValidationCheck workerCheck =
                validateWorkerRecipes(parsedStrategy, process);
        report.add(workerCheck);
        return workerCheck.isPassed() ? null : workerCheck;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected recipe YAML as a JSON object "
                + "with a valid name, engine: vogon, "
                + "params.strategyPlanYaml (parseable by Vogon), "
                + "and justifications all pointing to existing "
                + "sg-ids from the list above.";
    }

    // ──────────────────── Helpers ────────────────────

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
    private ValidationCheck validateWorkerRecipes(
            StrategySpec strategy, ThinkProcessDocument process) {
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
                + "doc_create, web_search, scratch_write) where a "
                + "RECIPE name is required. Tools are called inside a "
                + "worker's turn; the worker itself must be a recipe "
                + "with an engine bound to it.\n\n");
        msg.append("POSSIBLE OPTIONS ARE (pick exactly one of these "
                + "verbatim for each phase's worker: field):\n");
        List<String> ordered = new ArrayList<>();
        for (String standard : List.of(
                "ford", "marvin-worker", "analyze", "code-read")) {
            if (available.contains(standard)) ordered.add(standard);
        }
        for (String name : available) {
            if (!ordered.contains(name)) ordered.add(name);
        }
        for (String name : ordered) {
            msg.append("- '").append(name).append("'\n");
        }
        if (ordered.isEmpty()) {
            msg.append("- 'ford'\n");
            msg.append("- 'marvin-worker'\n");
        }
        msg.append("\nDefault when in doubt: 'ford' (generalist "
                + "single-task worker). Use 'marvin-worker' only for "
                + "long-form outputs (multi-chapter drafts) where one "
                + "Ford turn would be too much.");
        return ValidationCheck.builder()
                .rule(RULE_VOGON_WORKER_RECIPES_EXIST).passed(false)
                .message(msg.toString())
                .build();
    }

    private static void collectWorkerNames(
            List<PhaseSpec> phases, Set<String> out) {
        if (phases == null) return;
        for (PhaseSpec p : phases) {
            String w = p.getWorker();
            if (w != null && !w.isBlank() && !w.contains("${")) {
                out.add(w.trim());
            }
            LoopSpec loop = p.getLoop();
            if (loop != null) {
                collectWorkerNames(loop.getSubPhases(), out);
            }
        }
    }
}
