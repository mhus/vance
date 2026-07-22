package de.mhus.vance.api.slartibartfast;

/**
 * Which kind of plan-artifact the Slartibartfast run is asked to
 * produce. Determines the validator wired into VALIDATING and the
 * shape of the YAML written in PERSISTING.
 *
 * <p>See {@code specification/slartibartfast-engine.md} §4.
 */
public enum OutputSchemaType {
    /** Vogon strategy YAML (phases, gates, scorers, postActions).
     *  Validated by {@code StrategyResolver.parseStrategy}. */
    VOGON_STRATEGY,

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
}
