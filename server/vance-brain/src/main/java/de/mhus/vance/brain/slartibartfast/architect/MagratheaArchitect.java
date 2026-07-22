package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowParseException;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Magrathea-workflow architect. Produces a named workflow YAML (a
 * state-machine document: {@code start} + {@code states:}) — NOT a
 * recipe. Magrathea is a workflow-orchestration subsystem, not a
 * {@code ThinkEngine}, so there is no {@code engine:} field and no
 * child-process Slart could spawn + await.
 *
 * <p>The architect is therefore <b>author-only</b>:
 * <ul>
 *   <li>{@link #isRecipeOutput()} {@code = false} — VALIDATING skips
 *       the recipe-specific engine-field / justifications / path
 *       checks; {@link #validateDraftShape} is the single
 *       shape-validation entry point.</li>
 *   <li>{@link #persistsAtFlatPath()} {@code = true} — the workflow
 *       lands at {@code _vance/workflows/<name>.yaml}, the path the
 *       {@link MagratheaWorkflowLoader} resolves, so it is directly
 *       startable via {@code workflow_start}.</li>
 *   <li>The bundled {@code magrathea-architect} recipe sets
 *       {@code params.planOnly: true}, so the run ends at DONE after
 *       PERSISTING — no EXECUTING, no EXECUTION_VALIDATING. Running
 *       the workflow is a separate step (agent {@code workflow_start},
 *       scheduler, or REST).</li>
 * </ul>
 *
 * <p>Validation delegates to {@link MagratheaWorkflowLoader#validateYaml}
 * (the same parser the runtime freezes into {@code StartRecord}), then
 * checks that every {@code agent_task}'s {@code recipe:} reference
 * resolves to a known project recipe — the analog of
 * {@link VogonArchitect}'s worker-recipe check.
 *
 * <p>Only a bean when Magrathea is enabled
 * ({@code vance.services.magrathea=true}); otherwise
 * {@link MagratheaWorkflowLoader} is absent and a run requesting
 * {@code MAGRATHEA_WORKFLOW} fails cleanly with "no SchemaArchitect
 * bean registered".
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaArchitect implements SchemaArchitect {

    public static final String RULE_WORKFLOW_PARSES =
            "magrathea-workflow-parses";
    public static final String RULE_AGENT_TASK_RECIPES_EXIST =
            "magrathea-agent-task-recipes-exist";

    private static final String SYSTEM_PROMPT = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce a
            Magrathea WORKFLOW — a named state-machine document that
            the Magrathea subsystem runs later. This is NOT a recipe:
            there is no `engine:` field. The whole YAML IS the plan.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<workflow-name, kebab-case>",
                  "yaml":           "<full workflow YAML, see structure below>",
                  "justifications": {
                    "<constraint-key>": "<sg-id>",
                    "states.<state>.type": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this state graph — 1-2 sentences>"
                }

            YAML structure (mandatory parts marked):
                description: |
                  <1-2 sentences>
                version: "1"                       # optional
                parameters:                        # optional
                  <key>:
                    type: string                   # documentation only in v1
                    required: true                 # optional (default false)
                    default: <value>               # optional
                bounds:                            # optional, HARD stop
                  maxTotalCostUsd: <number>
                  maxWallclockSeconds: <number>
                  maxTaskSpawns: <number>
                allowedTools: [<tool-name>, ...]   # optional whitelist
                tags: [<tag>, ...]                 # optional
                start: <state-name>                # MANDATORY — must exist in states
                states:                            # MANDATORY — at least one
                  <state-name>:
                    type: <task-type>              # MANDATORY (see task types)
                    description: <what this state does>
                    timeoutSeconds: <int>          # optional (not for timer/condition/terminal)
                    storeAs: <var-key>             # optional — write the task output into a variable
                    on:                            # outcome → next state (exact match)
                      <outcome>: <state-name>
                    catch:                         # error-kind → next state
                      technical_error: <state-name>
                    retry:                         # optional — preempts the transition
                      maxAttempts: <int ≥ 1>
                      on: [technical_error, timeout]
                      backoffSeconds: <int ≥ 0>

            TRANSITIONS — resolved after every task completes, in order:
              1. (condition_task only) the matching `transitions:` branch;
              2. `on:` — exact match on the task's outcome string;
              3. `catch:` — the outcome interpreted as an error-kind;
              otherwise the run fails. `retry:` preempts all of these.
            Every `on:`/`catch:`/`transitions:` target MUST name a
            declared state. A `terminal` state ends the run.

            ERROR KINDS (for `catch:` and `retry.on:`):
              technical_error, business_error, agent_error, timeout,
              permission_error, human_rejected, cancelled

            TASK TYPES (the `type:` field) and their type-specific keys:

            - agent_task     — spawn a ThinkProcess via a recipe.
                recipe: <recipe-name>            # MANDATORY, must be a known recipe
                params: { prompt: "...", schema: {...} }
                Outcomes: success | agent_error
            - tool_task      — one direct tool call.
                tool: <tool-name>
                params: { ... }
                Outcomes: success | permission_error | technical_error
            - shell_task     — a shell command via ExecManager.
                run: "<command>"
                dirName: <workspace-subdir>       # optional
                Outcomes: success | business_error | timeout | technical_error
            - script_task    — a JS script via the unified script executor.
                (source per trigger-actions §4.4)
            - gate_task      — pause for a user Inbox decision.
                inbox: { kind: APPROVAL|DECISION|FEEDBACK, title: "...",
                         assignedTo: <user>, criticality: <level>,
                         options: [<opt>, ...] }
                Outcome depends on the user's answer.
            - timer_task     — wait.
                duration: 7d | 4h | 30m | <ISO-8601>
                Outcome: fired
            - condition_task — pure SpEL branch, no side effect.
                transitions:
                  - if: "#state['k'] == 'x'"      # SpEL; #state[...] and #params[...]
                    to: <state-name>
                  - else: <state-name>            # MUST be last
            - workflow_task  — spawn a sub-workflow and block on it.
                workflow: <workflow-name>
                params: { ... }
                Outcomes: success | failure
            - terminal       — end state.
                outcome: success | failure         # MANDATORY
                result: { ... }                    # optional result payload

            RULES:
            - Reference recipes in `agent_task.recipe` ONLY by names
              from the available-recipes list below (a tool name like
              `doc_edit` is NOT a recipe). Use `ford` for a
              generalist single-task worker when unsure.
            - Keep the graph acyclic where possible; guard every loop
              with a condition_task + a bound or counter.
            - At least one reachable `terminal` state.

            justifications map (mandatory): EVERY constraint-key you
            set MUST point to an sg-id that exists in subgoals.
            Convention: "name" for the workflow name,
            "start" for the entry state, "states.<state>.type" per
            state, "states.<state>.recipe" for agent tasks.

            confidence: 1.0 minus the speculative share.
            shapeRationale: WHY this state graph (this many states,
            this branching) — refers to the overall shape.

            Language: prompts and prose-style fields are read by
            downstream LLMs as orchestration code — write them in
            English. The user-facing content language is carried by
            the goal text.

            If you violate this contract the validator rejects your
            output and asks you to correct it.
            """;

    private final MagratheaWorkflowLoader workflowLoader;
    private final RecipeLoader recipeLoader;

    @Override
    public OutputSchemaType type() {
        return OutputSchemaType.MAGRATHEA_WORKFLOW;
    }

    @Override
    public boolean isRecipeOutput() {
        return false;
    }

    @Override
    public boolean persistsAtFlatPath() {
        return true;
    }

    @Override
    public String outputPathSegment() {
        return "workflows";
    }

    @Override
    public String outputExtension() {
        return ".yaml";
    }

    @Override
    public String artefactNoun() {
        return "workflow";
    }

    @Override
    public boolean wantsPathPersistenceCheck() {
        // A workflow's outputs are produced by its own tasks at run
        // time (agent/tool/shell), not by tool calls embedded in a
        // recipe YAML the substring check could see.
        return false;
    }

    @Override
    public boolean wantsExecutionValidation() {
        // Author-only (planOnly) — the run ends at DONE after
        // PERSISTING and never reaches EXECUTION_VALIDATING. Declared
        // false defensively so a caller who forgets planOnly does not
        // drive Slart into a pointless recovery loop over a workflow
        // that was never executed.
        return false;
    }

    @Override
    public String proposingSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public boolean wantsSubRecipeListing() {
        // agent_task states reference recipes by name — the LLM needs
        // the project recipe inventory to pick valid workers.
        return true;
    }

    @Override
    public void appendProposingContext(
            StringBuilder sb, ArchitectState state,
            List<ResolvedRecipe> availableRecipes) {
        sb.append("Available recipes for agent_task.recipe references "
                + "(excluding the _slart/* generated bucket):\n");
        if (availableRecipes.isEmpty()) {
            sb.append("  (none) — use 'ford' as the generalist worker.\n");
            return;
        }
        for (ResolvedRecipe r : availableRecipes) {
            sb.append("  - ").append(r.name())
                    .append(" [engine=").append(r.engine()).append("]: ")
                    .append(abbrev(r.description(), 100))
                    .append("\n");
        }
        sb.append("\nUse ONLY names from this list in "
                + "agent_task.recipe. Default to 'ford' when unsure.\n");
    }

    @Override
    public String expectedEngineName() {
        // Non-recipe output — ValidatingPhase skips the engine-field
        // check based on isRecipeOutput() returning false. Never
        // consulted in that path.
        return "";
    }

    @Override
    public @Nullable ValidationCheck validateDraftShape(
            RecipeDraft draft, @Nullable Map<String, Object> recipeMap,
            ThinkProcessDocument process, List<ValidationCheck> report) {
        // recipeMap is null for non-recipe schemas — work off the raw
        // YAML in draft.getYaml() through the Magrathea parser.
        ResolvedMagratheaWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(draft.getName(), draft.getYaml());
            report.add(ValidationCheck.builder()
                    .rule(RULE_WORKFLOW_PARSES).passed(true)
                    .message("workflow YAML parses cleanly")
                    .build());
        } catch (MagratheaWorkflowParseException e) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_WORKFLOW_PARSES).passed(false)
                    .message("MagratheaWorkflowLoader rejected the "
                            + "workflow: " + e.getMessage())
                    .build();
            report.add(v);
            return v;
        }

        ValidationCheck recipeCheck = validateAgentTaskRecipes(workflow, process);
        report.add(recipeCheck);
        return recipeCheck.isPassed() ? null : recipeCheck;
    }

    @Override
    public String recoveryHintTail(ThinkProcessDocument process) {
        return "\nEmit a corrected workflow YAML as a JSON object with "
                + "a valid name, a `start:` naming a declared state, a "
                + "non-empty `states:` map, every on/catch/transition "
                + "target pointing to a declared state, and every "
                + "agent_task.recipe referencing a known recipe from "
                + "the list above.";
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Every {@code agent_task}'s {@code recipe:} must resolve to a
     * known project recipe — the common failure is the LLM naming a
     * tool (e.g. {@code doc_edit}) where a recipe is required. Mirrors
     * {@link VogonArchitect}'s worker-recipe check.
     */
    private ValidationCheck validateAgentTaskRecipes(
            ResolvedMagratheaWorkflow workflow, ThinkProcessDocument process) {
        Set<String> recipesUsed = new LinkedHashSet<>();
        for (MagratheaStateSpec s : workflow.states().values()) {
            if (s.type() != MagratheaTaskType.AGENT_TASK) continue;
            String recipe = s.specString("recipe");
            if (recipe != null && !recipe.contains("${")) {
                recipesUsed.add(recipe.trim());
            }
        }
        if (recipesUsed.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_AGENT_TASK_RECIPES_EXIST).passed(true)
                    .message("no agent_task recipe references to validate")
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
                            + "recipes for agent_task-check: {}",
                    process.getId(), e.toString());
        }

        List<String> unknown = new ArrayList<>();
        for (String r : recipesUsed) {
            if (!available.contains(r)) unknown.add(r);
        }
        if (unknown.isEmpty()) {
            return ValidationCheck.builder()
                    .rule(RULE_AGENT_TASK_RECIPES_EXIST).passed(true)
                    .message(recipesUsed.size() + " agent_task recipe "
                            + "reference(s) resolve to known recipes")
                    .build();
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Workflow agent_task references unknown recipe(s): ")
                .append(unknown).append(".\n\n");
        msg.append("Common mistake: using a TOOL name (e.g. doc_edit, "
                + "web_search) where a RECIPE name is required. For a "
                + "single direct tool call use a tool_task, not an "
                + "agent_task.\n\n");
        msg.append("AVAILABLE RECIPES (pick exactly one verbatim for "
                + "each agent_task.recipe):\n");
        List<String> ordered = new ArrayList<>();
        if (available.contains("ford")) ordered.add("ford");
        for (String name : available) {
            if (!ordered.contains(name)) ordered.add(name);
        }
        if (ordered.isEmpty()) ordered.add("ford");
        for (String name : ordered) {
            msg.append("- '").append(name).append("'\n");
        }
        msg.append("\nDefault when in doubt: 'ford' (generalist "
                + "single-task worker).");
        return ValidationCheck.builder()
                .rule(RULE_AGENT_TASK_RECIPES_EXIST).passed(false)
                .message(msg.toString())
                .build();
    }

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        String trimmed = s.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
