package de.mhus.vance.api.slartibartfast;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Which kind of plan-artifact the Slartibartfast run is asked to
 * produce. Determines the validator wired into VALIDATING and the
 * shape of the YAML written in PERSISTING.
 *
 * <p>See {@code specification/slartibartfast-engine.md} §4.
 */
public enum OutputSchemaType {
    /**
     * A plan meant to be run on behalf of a person — same grammar as
     * {@link #MAGRATHEA_WORKFLOW}, written with different advice
     * ({@code VogonArchitect}) because the job is different: judgements
     * and iteration rather than commands and retries. Validated by the
     * one plan parser, {@code MagratheaWorkflowLoader}.
     *
     * <p>Was called {@code VOGON_STRATEGY}. The alias is what makes that
     * rename survivable: a Slart process that was running at deploy time has
     * the old name persisted in its {@code engineParams}, and
     * {@code SlartibartfastEngine.loadState} deserialises that state on
     * <em>every</em> further turn. Without the alias Jackson rejects it and
     * the process is terminally stuck — the lenient spawn-parameter fallback
     * never sees the persisted state.
     */
    @JsonAlias("VOGON_STRATEGY")
    VOGON_PLAN,

    /** Marvin recipe YAML — engineParams (allowedSubTaskRecipes
     *  etc.) plus promptPrefix. Statically validated for param
     *  shape; the runtime re-prompt loop in
     *  {@code MarvinEngine.validatePlanChildren} enforces it
     *  during the actual run. */
    MARVIN_RECIPE,

    /** Zaphod council recipe YAML — heads list (each with name,
     *  recipe, persona), plus a {@code synthesisPrompt} for the
     *  aggregator turn. Validated by {@code ZaphodHeadsParser}
     *  (shape + per-head required fields); the runtime Zaphod
     *  engine spawns one sub-process per head and runs the
     *  synthesis turn over their outputs. */
    ZAPHOD_RECIPE,

    /** Single-file JavaScript orchestrator script. NOT a recipe —
     *  the LLM emits {@code { name, code, justifications, shapeRationale }}
     *  and the body lands under {@code scripts/_slart/<runId>/<name>.js}.
     *  Validated by {@code JsScriptArchitect} via
     *  {@code HactarService.validate(...)} (parse + header + tool
     *  allowlist). The persisted script is executed at runtime by
     *  Hactar (Phase 3 of the architect/executor split). */
    SCRIPT_JS,

    /** Magrathea workflow YAML — a named state-machine document
     *  ({@code start} + {@code states:} with agent/tool/shell/script/
     *  gate/timer/condition/workflow/terminal tasks). NOT a recipe:
     *  it has no top-level {@code engine:} field and Magrathea is a
     *  workflow-orchestration subsystem, not a {@code ThinkEngine}.
     *  Validated by {@code MagratheaArchitect} via
     *  {@code MagratheaWorkflowLoader.validateYaml(...)}. Author-only —
     *  the Slart run persists the workflow to {@code _vance/workflows/
     *  <name>.yaml} (directly usable by {@code workflow_start}) and
     *  ends at DONE via the {@code planOnly} path; the workflow is run
     *  later through the Magrathea subsystem. */
    MAGRATHEA_WORKFLOW,

    /** Benjy recipe YAML — the outer configuration of the iterative
     *  orchestration engine for small/local models: {@code engine: benjy}
     *  plus the {@code params} shape the engine parses fail-fast at first
     *  loop entry ({@code doRecipe}, {@code taskTypes}, the {@code features}
     *  map with interpret/route/check/evaluate/reflect/escalation, safety-net
     *  caps, {@code workTarget}). Validated by {@code BenjyArchitect}: the
     *  shape check delegates to the engine's own
     *  {@code BenjyFeatureConfig.fromParams} (no parallel validation schema
     *  to drift) and resolves every referenced recipe (doer, controller
     *  LightLlm profiles, escalation target) via the {@code RecipeLoader}.
     *  The bundled {@code benjy-architect} recipe sets {@code planOnly: true}
     *  — author-only: a Benjy run is a long-lived iterative worker whose
     *  cost profile has no place inside an authoring run, so the generated
     *  recipe is spawned as a separate step afterwards. The architect
     *  references sub-recipes by name; it does not generate them.
     */
    BENJY_RECIPE,
}
